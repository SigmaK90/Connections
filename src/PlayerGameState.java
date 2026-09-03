import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rappresenta lo stato di gioco di un giocatore, con le informazioni necessarie per la persistenza e il calcolo del punteggio
 */
public class PlayerGameState {
    // Stato della partita per il giocatore
    public enum Status {
        IN_PROGRESS,
        WON,
        LOST,
        TIMED_OUT
    }

    private int gameId;
    private String username;
    private int mistakes = 0;
    private int maxMistakes = 4;
    private Status status = Status.IN_PROGRESS;

    // Insieme delle categorie indovinate
    private final Set<String> guessedCategoryNames = new HashSet<>();

    // Insieme delle parole indovinate
    private final Set<String> guessedWords = new HashSet<>();

    // Insieme delle proposte dell'utente
    private final List<List<String>> proposalHistory = new ArrayList<>();

    /**
     * Costruttore completo
     */
    public PlayerGameState(int gameId, String username, int maxMistakes) {
        this.gameId = gameId;
        this.username = username;
        this.maxMistakes = maxMistakes;
    }

    /**
     * Costruttore senza username
     */
    public PlayerGameState(int gameId, int maxMistakes) {
        this(gameId, null, maxMistakes);
    }

    /**
     * Costruttore senza gameID
     */
    public PlayerGameState(String username, int maxMistakes) {
        this(0, username, maxMistakes);
    }

    // Getters e setters
    public int getGameId() {
        return gameId;
    }

    public void setGameId(int gameId) {
        this.gameId = gameId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getMistakes() {
        return mistakes;
    }

    public int getMaxMistakes() {
        return maxMistakes;
    }

    public int getErrorsLeft() {
        return Math.max(0, maxMistakes - mistakes);
    }

    /**
     * Metodo che calcola il punteggio dell'utente in base alle categorie indovinate e agli errori commessi
     */
    public int getScore() {
        int correctCount = guessedCategoryNames.size();
        int bonus = 0;
        
        if (correctCount == 1) {
            bonus = 6;
        } else if (correctCount == 2) {
            bonus = 12;
        } else if (correctCount >= 3) {
            bonus = 18;
        }

        int penalty = mistakes * 4;
        return bonus - penalty;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /**
     * Verifica se la partita sia terminata (Non in corso)
     */
    public boolean isGameOver() {
        return status != Status.IN_PROGRESS;
    }

    /**
     * Restituisce una copia dell'insieme delle categorie indovinate e delle parole indovinate, per evitare modifiche esterne
     */
    public Set<String> getGuessedCategoryNames() {
        return new HashSet<>(guessedCategoryNames);
    }

    /**
     * Restituisce una copia dell'insieme delle parole indovinate, per evitare modifiche esterne
     */
    public Set<String> getGuessedWords() {
        return new HashSet<>(guessedWords);
    }

    /**
     * Restituisce il numero di categorie indovinate
     */
    public int getGuessedCategoriesCount() {
        return guessedCategoryNames.size();
    }

    /**
     * Verifica se una parola è nell'insieme delle parole già indovinate
     */
    public boolean isWordAlreadyGuessed(String word) {
        return guessedWords.contains(word.trim().toLowerCase());
    }

    /**
     * Aggiunge una categoria all'insieme delle categorie indovinate, e le parole corrispondenti a quello delle parole indovinate
     */
    public void addGuessedCategory(String categoryName, Set<String> words) {
        guessedCategoryNames.add(categoryName);
        for (String w : words) {
            guessedWords.add(w.trim().toLowerCase());
        }
        
        // Con 3 gruppi indovinati la partita è vinta (il 4° è implicito)
        if (guessedCategoryNames.size() >= 3) {
            this.status = Status.WON;
        }
    }

    /**
     * Incrementa il numero di errori commessi, al quarto errore imposta lo stato a "perso"
     */
    public void incrementMistakes() {
        this.mistakes++;
        if (this.mistakes >= maxMistakes) {
            this.status = Status.LOST;
        }
    }

    /**
     * Metodo thread-safe per aggiungere una proposta alla cronologia delle proposte dell'utente.
     */
    public synchronized void addProposalToHistory(List<String> proposal) {
        this.proposalHistory.add(new ArrayList<>(proposal));
    }

    /**
     * Metodo thread-safe per ottenere una copia della cronologia delle proposte dell'utente.
     */
    public synchronized List<List<String>> getProposalHistory() {
        return new ArrayList<>(proposalHistory);
    }
}