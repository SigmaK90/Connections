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
import java.util.concurrent.locks.ReentrantLock;

public class GameManager {

    public enum GamePhase {
        GAME_ACTIVE,
        IN_PAUSE
    }

    @FunctionalInterface
    public interface GameNotificationCallback {
        void onEvent(int gameId, String message);
    }

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
        
        // CORREZIONE RIAVVIO SERVER: riallinea l'indice dei puzzle basandosi sullo storico
        if (loadedPuzzles != null && !loadedPuzzles.isEmpty()) {
            this.currentPuzzleIndex = completedGames.size() % loadedPuzzles.size();
        }

        startNextGame();
        this.scheduler.scheduleAtFixedRate(this::checkAndNextGame, 1, 1, TimeUnit.SECONDS);
    }

    public void setOnGameStartCallback(GameNotificationCallback onGameStartCallback) {
        this.onGameStartCallback = onGameStartCallback;
    }

    public void setOnPauseStartCallback(GameNotificationCallback onPauseStartCallback) {
        this.onPauseStartCallback = onPauseStartCallback;
    }

    private void loadPuzzles() {
        gameLock.lock();
        try {
            this.loadedPuzzles = new ArrayList<>();
            try (FileReader reader = new FileReader(gamesFilePath)) {
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

    private void loadHistory() {
        gameLock.lock();
        try {
            File file = new File(historyFilePath);
            if (!file.exists()) {
                System.out.println("[GAME MANAGER] Nessun file storico trovato. Verrà creato a fine round.");
                return;
            }
            try (FileReader reader = new FileReader(file)) {
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

    private void saveHistory() {
        gameLock.lock();
        try (Writer writer = new FileWriter(historyFilePath)) {
            gson.toJson(completedGames, writer);
        } catch (IOException e) {
            System.err.println("[GAME MANAGER] Errore durante il salvataggio dello storico su JSON: " + e.getMessage());
        } finally {
            gameLock.unlock();
        }
    }

    public void checkAndNextGame() {
        gameLock.lock();
        try {
            long elapsed = System.currentTimeMillis() - currentPhaseStartTime;

            if (currentPhase == GamePhase.GAME_ACTIVE) {
                if (elapsed >= gameDurationMillis) {
                    startPause();
                }
            } else if (currentPhase == GamePhase.IN_PAUSE) {
                if (elapsed >= pauseDurationMillis) {
                    startNextGame();
                }
            }
        } finally {
            gameLock.unlock();
        }
    }

    private void startPause() {
        gameLock.lock();
        try {
            this.currentPhase = GamePhase.IN_PAUSE;
            this.currentPhaseStartTime = System.currentTimeMillis();

            if (currentPuzzle != null) {
                completedGames.put(currentPuzzle.getGameId(), new CompletedGameRecord(currentPuzzle, playerStates));
                saveHistory();

                for (Map.Entry<String, PlayerGameState> entry : playerStates.entrySet()) {
                    String username = entry.getKey();
                    PlayerGameState state = entry.getValue();
                    synchronized (state) {
                        boolean won = (state.getStatus() == PlayerGameState.Status.WON);
                        userManager.recordCompletedGame(username, state.getScore(), won, state.getMistakes());
                    }
                }
            }

            int gameId = currentPuzzle != null ? currentPuzzle.getGameId() : -1;
            System.out.println("[GAME MANAGER] Partita " + gameId + " terminata. Inizio pausa.");

            if (onPauseStartCallback != null) {
                onPauseStartCallback.onEvent(gameId, "Partita " + gameId + " terminata.");
            }
        } finally {
            gameLock.unlock();
        }
    }

    private void startNextGame() {
        gameLock.lock();
        try {
            if (loadedPuzzles == null || loadedPuzzles.isEmpty()) return;

            this.currentPuzzle = loadedPuzzles.get(currentPuzzleIndex % loadedPuzzles.size());
            this.currentPuzzleIndex++;
            this.currentPhaseStartTime = System.currentTimeMillis();
            this.currentPhase = GamePhase.GAME_ACTIVE;
            this.playerStates.clear();

            System.out.println("[GAME MANAGER] Avviata nuova partita ID: " + currentPuzzle.getGameId());

            if (onGameStartCallback != null) {
                onGameStartCallback.onEvent(currentPuzzle.getGameId(), "Nuova partita disponibile! ID: " + currentPuzzle.getGameId());
            }
        } finally {
            gameLock.unlock();
        }
    }

    public Map<String, Object> getGameInfo(Integer targetGameId, String username) {
        checkAndNextGame();
        gameLock.lock();
        try {
            // CORREZIONE CASO 1: Controlla prima se è una partita salvata nello storico
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
            info.put("remainingTimeSeconds", getRemainingTimeSeconds());

            if (state != null) {
                synchronized (state) {
                    info.put("mistakes", state.getMistakes());
                    info.put("maxMistakes", state.getMaxMistakes());
                    info.put("status", state.getStatus().toString());
                    info.put("guessedCategories", state.getGuessedCategoryNames());
                    info.put("score", state.getScore());
                }
            }
            return info;
        } finally {
            gameLock.unlock();
        }
    }

    public Map<String, Object> getGameStats(Integer targetGameId) {
        checkAndNextGame();
        gameLock.lock();
        try {
            // CORREZIONE CASO 1: Controlla prima se è nello storico
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

            // CASO 2: Partita corrente in corso
            if (targetGameId == null || (currentPuzzle != null && currentPuzzle.getGameId() == targetGameId)) {
                return getCurrentGameStats();
            }

            return null;
        } finally {
            gameLock.unlock();
        }
    }

    public Map<String, Object> getCurrentGameStats() {
        checkAndNextGame();
        gameLock.lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("gameId", currentPuzzle != null ? currentPuzzle.getGameId() : -1);
            stats.put("phase", currentPhase.toString());
            stats.put("remainingTimeSeconds", getRemainingTimeSeconds());
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

                    // CORREZIONE CONTATORI ESPLICITI COME DA SPECIFICA
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
        } finally {
            gameLock.unlock();
        }
    }

    public PlayerGameState getOrCreatePlayerState(String username) {
        checkAndNextGame();
        gameLock.lock();
        try {
            if (currentPhase == GamePhase.IN_PAUSE || currentPuzzle == null) {
                return null;
            }
            int currentGameId = currentPuzzle.getGameId();
            return playerStates.computeIfAbsent(username, u -> new PlayerGameState(currentGameId, maxMistakes));
        } finally {
            gameLock.unlock();
        }
    }

    public ProposalResult submitProposal(String username, List<String> userWords) {
        checkAndNextGame();
        gameLock.lock();
        try {
            if (currentPhase == GamePhase.IN_PAUSE) {
                return new ProposalResult(false, "Il gioco è in pausa. Prossima partita tra " + getRemainingTimeSeconds() + "s.", true, false);
            }

            PlayerGameState state = getOrCreatePlayerState(username);
            if (state == null) {
                return new ProposalResult(false, "Impossibile partecipare alla partita corrente.", true, false);
            }

            synchronized (state) {
                if (state.isGameOver()) {
                    return new ProposalResult(false, "Hai già completato la partita corrente. Attendi la prossima.", true, false);
                }

                if (userWords == null || userWords.size() != 4) {
                    return new ProposalResult(false, "Servono 4 parole.", true, false);
                }

                List<String> cleanedWords = userWords.stream().map(String::trim).map(String::toLowerCase).toList();

                if (new HashSet<>(cleanedWords).size() < 4) {
                    return new ProposalResult(false, "Parole duplicate inserite.", true, false);
                }

                List<String> validPuzzleWords = currentPuzzle.getAllWordsShuffled().stream().map(String::trim).map(String::toLowerCase).toList();

                for (String w : cleanedWords) {
                    if (!validPuzzleWords.contains(w)) {
                        return new ProposalResult(false, "La parola '" + w + "' non appartiene a questa partita.", true, false);
                    }
                    if (state.isWordAlreadyGuessed(w)) {
                        return new ProposalResult(false, "La parola '" + w + "' fa già parte di un gruppo indovinato.", true, false);
                    }
                }

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
                    return new ProposalResult(true, msg, false, false);
                } else {
                    state.incrementMistakes();
                    String msg = "Proposta errata.";
                    if (oneAway) {
                        msg += " (3 parole su 4 sono dello stesso gruppo!)";
                    }
                    if (state.getStatus() == PlayerGameState.Status.LOST) {
                        msg += " Limite massimo errori raggiunto. Partita persa!";
                    }
                    return new ProposalResult(false, msg, false, oneAway);
                }
            }
        } finally {
            gameLock.unlock();
        }
    }

    public Puzzle getCurrentPuzzle() {
        checkAndNextGame();
        gameLock.lock();
        try {
            return currentPuzzle;
        } finally {
            gameLock.unlock();
        }
    }

    public GamePhase getCurrentPhase() {
        checkAndNextGame();
        gameLock.lock();
        try {
            return currentPhase;
        } finally {
            gameLock.unlock();
        }
    }

    public long getRemainingTimeSeconds() {
        checkAndNextGame();
        gameLock.lock();
        try {
            long elapsed = System.currentTimeMillis() - currentPhaseStartTime;
            long targetDuration = (currentPhase == GamePhase.GAME_ACTIVE) ? gameDurationMillis : pauseDurationMillis;
            return Math.max(0, (targetDuration - elapsed) / 1000);
        } finally {
            gameLock.unlock();
        }
    }

    public void stop() {
        this.scheduler.shutdown();
    }

    public static class ProposalResult {
        private final boolean correct;
        private final String message;
        private final boolean formalError;
        private final boolean oneAway;

        public ProposalResult(boolean correct, String message, boolean formalError, boolean oneAway) {
            this.correct = correct;
            this.message = message;
            this.formalError = formalError;
            this.oneAway = oneAway;
        }

        public boolean isCorrect() { return correct; }
        public String getMessage() { return message; }
        public boolean isFormalError() { return formalError; }
        public boolean isOneAway() { return oneAway; }
    }
}