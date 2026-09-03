import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestore principale della logica di gioco di Connections.
 * Controlla il ciclo di vita delle partite (fasi attive e di pausa), la persistenza dello storico,
 * la validazione delle proposte di parole e il tracciamento dei punteggi e degli errori dei giocatori.
 */
public class GameManager {

    /**
     * Rappresenta le fasi possibili del ciclo di gioco.
     */
    public enum GamePhase {
        GAME_ACTIVE,
        IN_PAUSE
    }

    /**
     * Interfaccia funzionale per la gestione delle notifiche asincrone di cambio fase.
     */
    @FunctionalInterface
    public interface GameNotificationCallback {
        void onEvent(int gameId, String message);
    }

    /**
     * Memorizza lo stato finale e la configurazione di un puzzle per una partita completata.
     */
    public static class CompletedGameRecord {
        private final Puzzle puzzle;
        private final Map<String, PlayerGameState> finalPlayerStates;

        public CompletedGameRecord(Puzzle puzzle, Map<String, PlayerGameState> finalPlayerStates) {
            this.puzzle = puzzle;
            this.finalPlayerStates = new HashMap<>(finalPlayerStates);
        }

        public Puzzle getPuzzle() { return puzzle; }
        public Map<String, PlayerGameState> getFinalPlayerStates() { return finalPlayerStates; }
    }

    private final String gamesFilePath;
    private final String historyFilePath;
    private final long gameDurationMillis;
    private final long pauseDurationMillis;
    private final int maxMistakes;
    private final UserManager userManager;
    private final Gson gson;

    private List<Puzzle> loadedPuzzles;
    private int currentPuzzleIndex = 0;
    
    private Puzzle currentPuzzle;
    private long currentPhaseStartTime;
    private GamePhase currentPhase = GamePhase.IN_PAUSE;

    private final ReentrantLock gameLock = new ReentrantLock();
    private final Map<String, PlayerGameState> playerStates = new ConcurrentHashMap<>();
    private final Map<Integer, CompletedGameRecord> completedGames = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private GameNotificationCallback onGameStartCallback;
    private GameNotificationCallback onPauseStartCallback;

    /**
     * Inizializza il GameManager, carica i puzzle dal file JSON, ripristina lo storico
     * e avvia il timer periodico per la gestione del ciclo di gioco.
     */
    public GameManager(String gamesFilePath, String historyFilePath, long gameDurationMillis, long pauseDurationMillis, int maxMistakes, UserManager userManager) {
        this.gamesFilePath = gamesFilePath;
        this.historyFilePath = historyFilePath;
        this.gameDurationMillis = gameDurationMillis;
        this.pauseDurationMillis = pauseDurationMillis;
        this.maxMistakes = maxMistakes;
        this.userManager = userManager;
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        loadPuzzles();
        loadHistory();
        
        // Riallinea l'indice dei puzzle basandosi sullo storico salvato
        if (loadedPuzzles != null && !loadedPuzzles.isEmpty()) {
            this.currentPuzzleIndex = completedGames.size() % loadedPuzzles.size();
        }

        gameLock.lock();
        try {
            startNextGameLocked();
        } finally {
            gameLock.unlock();
        }

        this.scheduler.scheduleAtFixedRate(this::checkAndNextGame, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Imposta il callback da invocare quando una nuova partita inizia.
     */
    public void setOnGameStartCallback(GameNotificationCallback onGameStartCallback) {
        this.onGameStartCallback = onGameStartCallback;
    }
    
    /**
     * Imposta il callback da invocare quando una partita termina e inizia la pausa.
     */
    public void setOnPauseStartCallback(GameNotificationCallback onPauseStartCallback) {
        this.onPauseStartCallback = onPauseStartCallback;
    }

    /**
     * Carica in modo sicuro l'elenco dei puzzle dal file JSON specificato.
     */
    private void loadPuzzles() {
        gameLock.lock();
        try {
            this.loadedPuzzles = new ArrayList<>();
            try (FileReader reader = new FileReader(gamesFilePath, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<Puzzle>>() {}.getType();
                this.loadedPuzzles = gson.fromJson(reader, listType);
                System.out.println("[GAME MANAGER] Caricati " + (loadedPuzzles != null ? loadedPuzzles.size() : 0) + " puzzle dal file JSON.");
            } catch (IOException e) {
                System.err.println("[GAME MANAGER] Errore nel caricamento del file partite: " + e.getMessage());
            }
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Carica lo storico delle partite precedentemente giocate dal file JSON.
     */
    private void loadHistory() {
        gameLock.lock();
        try {
            File file = new File(historyFilePath);
            if (!file.exists()) {
                System.out.println("[GAME MANAGER] Nessun file storico trovato. Verrà creato a fine round.");
                return;
            }
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                Type mapType = new TypeToken<Map<Integer, CompletedGameRecord>>() {}.getType();
                Map<Integer, CompletedGameRecord> loaded = gson.fromJson(reader, mapType);
                if (loaded != null) {
                    completedGames.clear();
                    completedGames.putAll(loaded);
                    System.out.println("[GAME MANAGER] Caricate " + completedGames.size() + " partite dallo storico.");
                }
            } catch (IOException e) {
                System.err.println("[GAME MANAGER] Errore nel caricamento dello storico: " + e.getMessage());
            }
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Salva lo storico aggiornato delle partite su file JSON. Deve essere chiamato all'interno di un blocco protetto da gameLock.
     */
    private void saveHistoryLocked() {
        try (Writer writer = new FileWriter(historyFilePath, StandardCharsets.UTF_8)) {
            gson.toJson(completedGames, writer);
        } catch (IOException e) {
            System.err.println("[GAME MANAGER] Errore durante il salvataggio dello storico su JSON: " + e.getMessage());
        }
    }

    /**
     * Metodo invocato dal timer per verificare la scadenza della fase corrente.
     */
    public void checkAndNextGame() {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Controlla se il tempo trascorso nella fase attuale supera la durata prevista.
     */
    private void checkPhaseExpirationLocked() {
        long elapsed = System.currentTimeMillis() - currentPhaseStartTime;

        if (currentPhase == GamePhase.GAME_ACTIVE) {
            if (elapsed >= gameDurationMillis) {
                startPauseLocked();
            }
        } else if (currentPhase == GamePhase.IN_PAUSE) {
            if (elapsed >= pauseDurationMillis) {
                startNextGameLocked();
            }
        }
    }

    /**
     * Termina la partita corrente, registra le statistiche dei giocatori, aggiorna lo storico e avvia la fase di pausa.
     */
    private void startPauseLocked() {
        this.currentPhase = GamePhase.IN_PAUSE;
        this.currentPhaseStartTime = System.currentTimeMillis();

        if (currentPuzzle != null) {
            // Aggiorna lo stato dei giocatori ancora in corso a TIMED_OUT
            for (Map.Entry<String, PlayerGameState> entry : playerStates.entrySet()) {
                String username = entry.getKey();
                PlayerGameState state = entry.getValue();
                synchronized (state) {
                    if (state.getStatus() == PlayerGameState.Status.IN_PROGRESS) {
                        state.setStatus(PlayerGameState.Status.TIMED_OUT);
                        userManager.recordCompletedGame(username, state.getScore(), false, state.getMistakes(), true);
                    } else {
                        boolean won = (state.getStatus() == PlayerGameState.Status.WON);
                        userManager.recordCompletedGame(username, state.getScore(), won, state.getMistakes(), false);
                    }
                }
            }

            completedGames.put(currentPuzzle.getGameId(), new CompletedGameRecord(currentPuzzle, playerStates));
            saveHistoryLocked();
        }

        int gameId = currentPuzzle != null ? currentPuzzle.getGameId() : -1;
        System.out.println("[GAME MANAGER] Partita " + gameId + " terminata. Inizio pausa.");

        if (onPauseStartCallback != null && currentPuzzle != null) {
            Map<String, Object> endPayload = new HashMap<>();
            endPayload.put("event", "GAME_ENDED");
            endPayload.put("gameId", gameId);
            endPayload.put("message", "Partita " + gameId + " terminata. Inizio pausa.");
            
            // Soluzione completa
            List<Map<String, Object>> solution = new ArrayList<>();
            for (Category cat : currentPuzzle.getGroups()) {
                Map<String, Object> group = new HashMap<>();
                group.put("theme", cat.getTheme());
                group.put("words", cat.getWords());
                solution.add(group);
            }
            endPayload.put("solution", solution);

            onPauseStartCallback.onEvent(gameId, gson.toJson(endPayload));
        }
    }

    /**
     * Seleziona il prossimo puzzle e avvia una nuova partita attiva.
     */
    private void startNextGameLocked() {
        if (loadedPuzzles == null || loadedPuzzles.isEmpty()) return;

        this.currentPuzzle = loadedPuzzles.get(currentPuzzleIndex % loadedPuzzles.size());
        this.currentPuzzleIndex++;
        this.currentPhaseStartTime = System.currentTimeMillis();
        this.currentPhase = GamePhase.GAME_ACTIVE;
        this.playerStates.clear();

        System.out.println("[GAME MANAGER] Avviata nuova partita ID: " + currentPuzzle.getGameId());

        if (onGameStartCallback != null) {
            Map<String, Object> startPayload = new HashMap<>();
            startPayload.put("event", "GAME_STARTED");
            startPayload.put("gameId", currentPuzzle.getGameId());
            startPayload.put("words", currentPuzzle.getAllWordsShuffled());
            startPayload.put("gameDurationSeconds", gameDurationMillis / 1000);
            startPayload.put("message", "Nuova partita avviata!");

            onGameStartCallback.onEvent(currentPuzzle.getGameId(), gson.toJson(startPayload));
        }
    }

    /**
     * Recupera le informazioni della partita specificata o della partita corrente.
     */
    public Map<String, Object> getGameInfo(Integer targetGameId, String username) {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();

            // CASO 1: Partita salvata nello storico
            if (targetGameId != null && completedGames.containsKey(targetGameId)) {
                CompletedGameRecord record = completedGames.get(targetGameId);

                Map<String, Object> info = new HashMap<>();
                info.put("gameId", record.getPuzzle().getGameId());
                info.put("status", "COMPLETED");

                List<Map<String, Object>> solutionGroups = new ArrayList<>();
                for (Category cat : record.getPuzzle().getGroups()) {
                    Map<String, Object> groupMap = new HashMap<>();
                    groupMap.put("theme", cat.getTheme());
                    groupMap.put("words", cat.getWords());
                    solutionGroups.add(groupMap);
                }
                info.put("solution", solutionGroups);

                PlayerGameState userState = record.getFinalPlayerStates().get(username);
                if (userState != null) {
                    synchronized (userState) {
                        info.put("userScore", userState.getScore());
                        info.put("userMistakes", userState.getMistakes());
                        info.put("userStatus", userState.getStatus().toString());
                    }
                }
                return info;
            }

            // CASO 2: Partita corrente
            if (currentPuzzle == null) return null;

            PlayerGameState state = (username != null) ? playerStates.get(username) : null;

            Map<String, Object> info = new HashMap<>();
            info.put("gameId", currentPuzzle.getGameId());
            info.put("words", currentPuzzle.getAllWordsShuffled());
            info.put("phase", currentPhase.toString());
            info.put("remainingTimeSeconds", getRemainingTimeSecondsLocked());

            if (state != null) {
                synchronized (state) {
                    info.put("mistakes", state.getMistakes());
                    info.put("maxMistakes", state.getMaxMistakes());
                    info.put("status", state.getStatus().toString());
                    info.put("guessedCategories", state.getGuessedCategoryNames());
                    info.put("score", state.getScore());
                }
            } else {
                info.put("mistakes", 0);
                info.put("maxMistakes", maxMistakes);
                info.put("status", PlayerGameState.Status.IN_PROGRESS.toString());
                info.put("guessedCategories", Collections.emptySet());
                info.put("score", 0);
            }
            return info;
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Restituisce le statistiche aggregate di una partita conclusa o corrente.
     */
    public Map<String, Object> getGameStats(Integer targetGameId) {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();

            // CASO 1: Partita nello storico
            if (targetGameId != null && completedGames.containsKey(targetGameId)) {
                CompletedGameRecord record = completedGames.get(targetGameId);

                Map<String, Object> stats = new HashMap<>();
                stats.put("gameId", record.getPuzzle().getGameId());
                stats.put("status", "COMPLETED");

                Map<String, PlayerGameState> states = record.getFinalPlayerStates();
                stats.put("totalParticipants", states.size());

                List<String> winners = new ArrayList<>();
                List<String> finishedPlayers = new ArrayList<>();
                double totalScore = 0;

                for (Map.Entry<String, PlayerGameState> entry : states.entrySet()) {
                    PlayerGameState st = entry.getValue();
                    synchronized (st) {
                        totalScore += st.getScore();
                        if (st.isGameOver()) {
                            finishedPlayers.add(entry.getKey());
                        }
                        if (st.getStatus() == PlayerGameState.Status.WON) {
                            winners.add(entry.getKey());
                        }
                    }
                }

                stats.put("winners", winners);
                stats.put("finishedPlayers", finishedPlayers);
                stats.put("averageScore", states.isEmpty() ? 0.0 : totalScore / states.size());

                return stats;
            }

            // CASO 2: Partita corrente
            if (targetGameId == null || (currentPuzzle != null && currentPuzzle.getGameId() == targetGameId)) {
                return getCurrentGameStatsLocked();
            }

            return null;
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Restituisce le statistiche aggregate della partita corrente. (un wrapper di getCurrentGameStatsLocked() con lock)
     */
    public Map<String, Object> getCurrentGameStats() {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();
            return getCurrentGameStatsLocked();
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Calcola la classifica e lo stato della partita in corso.
     */
    private Map<String, Object> getCurrentGameStatsLocked() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("gameId", currentPuzzle != null ? currentPuzzle.getGameId() : -1);
        stats.put("phase", currentPhase.toString());
        stats.put("remainingTimeSeconds", getRemainingTimeSecondsLocked());
        stats.put("totalParticipants", playerStates.size());

        int playersInProgress = 0;
        int finishedPlayers = 0;
        int winnersCount = 0;

        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Map.Entry<String, PlayerGameState> entry : playerStates.entrySet()) {
            Map<String, Object> pMap = new HashMap<>();
            PlayerGameState st = entry.getValue();
            synchronized (st) {
                pMap.put("username", entry.getKey());
                pMap.put("score", st.getScore());
                pMap.put("mistakes", st.getMistakes());
                pMap.put("status", st.getStatus().toString());
                pMap.put("categoriesFound", st.getGuessedCategoriesCount());

                if (st.getStatus() == PlayerGameState.Status.IN_PROGRESS) {
                    playersInProgress++;
                } else {
                    finishedPlayers++;
                    if (st.getStatus() == PlayerGameState.Status.WON) {
                        winnersCount++;
                    }
                }
            }
            playersInfo.add(pMap);
        }

        stats.put("playersInProgress", playersInProgress);
        stats.put("finishedPlayers", finishedPlayers);
        stats.put("winnersCount", winnersCount);

        playersInfo.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        stats.put("leaderboard", playersInfo);

        return stats;
    }

    /**
     * Ottiene o crea lo stato individuale di un utente per la partita corrente.
     */
    public PlayerGameState getOrCreatePlayerState(String username) {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();
            if (currentPhase == GamePhase.IN_PAUSE || currentPuzzle == null || username == null) {
                return null;
            }
            int currentGameId = currentPuzzle.getGameId();
            return playerStates.computeIfAbsent(username, u -> new PlayerGameState(currentGameId, username, maxMistakes));
        } finally {
            gameLock.unlock();
        }
    }

    /**
     * Valuta una proposta di 4 parole inoltrata da un utente, aggiornando il suo stato di gioco.
     */
    public ProposalResult submitProposal(String username, List<String> userWords) {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();

            if (currentPhase == GamePhase.IN_PAUSE) {
                return new ProposalResult(false, "Il gioco è in pausa. Prossima partita tra " + getRemainingTimeSecondsLocked() + "s.", true, false, null);
            }

            if (username == null) {
                return new ProposalResult(false, "Utente non valido.", true, false, null);
            }

            int currentGameId = currentPuzzle.getGameId();
            PlayerGameState state = playerStates.computeIfAbsent(username, u -> new PlayerGameState(currentGameId, username, maxMistakes));

            synchronized (state) {
                if (state.isGameOver()) {
                    return new ProposalResult(false, "Hai già completato la partita corrente. Attendi la prossima.", true, false, state);
                }

                if (userWords == null || userWords.size() != 4) {
                    return new ProposalResult(false, "Servono 4 parole.", true, false, state);
                }

                List<String> cleanedWords = userWords.stream().map(String::trim).map(String::toLowerCase).toList();

                if (new HashSet<>(cleanedWords).size() < 4) {
                    return new ProposalResult(false, "Parole duplicate inserite.", true, false, state);
                }

                List<String> validPuzzleWords = currentPuzzle.getAllWordsShuffled().stream().map(String::trim).map(String::toLowerCase).toList();

                for (String w : cleanedWords) {
                    if (!validPuzzleWords.contains(w)) {
                        return new ProposalResult(false, "La parola '" + w + "' non appartiene a questa partita.", true, false, state);
                    }
                    if (state.isWordAlreadyGuessed(w)) {
                        return new ProposalResult(false, "La parola '" + w + "' fa già parte di un gruppo indovinato.", true, false, state);
                    }
                }

                state.addProposalToHistory(cleanedWords);

                Category matchedCategory = null;
                boolean oneAway = false;

                for (Category category : currentPuzzle.getGroups()) {
                    if (category.matches(cleanedWords)) {
                        matchedCategory = category;
                        break;
                    }
                    if (category.countMatches(cleanedWords) == 3) {
                        oneAway = true;
                    }
                }

                if (matchedCategory != null) {
                    Set<String> categoryWords = new HashSet<>(matchedCategory.getWords().stream().map(String::trim).map(String::toLowerCase).toList());
                    state.addGuessedCategory(matchedCategory.getTheme(), categoryWords);
                    
                    String msg = "Categoria trovata: " + matchedCategory.getTheme();
                    if (state.getStatus() == PlayerGameState.Status.WON) {
                        msg += " - VITTORIA!";
                    }
                    return new ProposalResult(true, msg, false, false, state);
                } else {
                    state.incrementMistakes();
                    String msg = "Proposta errata.";
                    if (oneAway) {
                        msg += " (3 parole su 4 sono dello stesso gruppo!)";
                    }
                    if (state.getStatus() == PlayerGameState.Status.LOST) {
                        msg += " Limite massimo errori raggiunto. Partita persa!";
                    }
                    return new ProposalResult(false, msg, false, oneAway, state);
                }
            }
        } finally {
            gameLock.unlock();
        }
    }

    // Getters thread-safe per lo stato corrente del gioco
    public Puzzle getCurrentPuzzle() {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();
            return currentPuzzle;
        } finally {
            gameLock.unlock();
        }
    }

    public GamePhase getCurrentPhase() {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();
            return currentPhase;
        } finally {
            gameLock.unlock();
        }
    }

    public long getRemainingTimeSeconds() {
        gameLock.lock();
        try {
            checkPhaseExpirationLocked();
            return getRemainingTimeSecondsLocked();
        } finally {
            gameLock.unlock();
        }
    }

    private long getRemainingTimeSecondsLocked() {
        long elapsed = System.currentTimeMillis() - currentPhaseStartTime;
        long targetDuration = (currentPhase == GamePhase.GAME_ACTIVE) ? gameDurationMillis : pauseDurationMillis;
        return Math.max(0, (targetDuration - elapsed) / 1000);
    }

    /**
     * Arresta il timer del gestore di gioco.
     */
    public void stop() {
        this.scheduler.shutdown();
    }

    /**
     * Incapsula l'esito di una proposta inviata dal giocatore.
     */
    public static class ProposalResult {
        private final boolean correct;
        private final String message;
        private final boolean formalError;
        private final boolean oneAway;
        private final int mistakes;
        private final int maxMistakes;
        private final int errorsLeft;
        private final int score;
        private final PlayerGameState.Status status;
        private final Set<String> guessedCategories;

        /**
         * Costruisce un oggetto con i parametri specificati.
         */
        public ProposalResult(boolean correct, String message, boolean formalError, boolean oneAway, PlayerGameState state) {
            this.correct = correct;
            this.message = message;
            this.formalError = formalError;
            this.oneAway = oneAway;
            if (state != null) {
                synchronized (state) {
                    this.mistakes = state.getMistakes();
                    this.maxMistakes = state.getMaxMistakes();
                    this.errorsLeft = state.getErrorsLeft();
                    this.score = state.getScore();
                    this.status = state.getStatus();
                    this.guessedCategories = state.getGuessedCategoryNames();
                }
            } else {
                this.mistakes = 0;
                this.maxMistakes = 4;
                this.errorsLeft = 4;
                this.score = 0;
                this.status = PlayerGameState.Status.IN_PROGRESS;
                this.guessedCategories = Collections.emptySet();
            }
        }
        public boolean isCorrect() { return correct; }
        public String getMessage() { return message; }
        public boolean isFormalError() { return formalError; }
        public boolean isOneAway() { return oneAway; }
        public int getMistakes() { return mistakes; }
        public int getMaxMistakes() { return maxMistakes; }
        public int getErrorsLeft() { return errorsLeft; }
        public int getScore() { return score; }
        public PlayerGameState.Status getStatus() { return status; }
        public Set<String> getGuessedCategories() { return guessedCategories; }
    }
}