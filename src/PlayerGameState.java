import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlayerGameState {

    public enum Status {
        IN_PROGRESS,
        WON,
        LOST
    }

    private int gameId;
    private String username;
    private int mistakes = 0;
    private int maxMistakes = 4;
    private Status status = Status.IN_PROGRESS;
    
    private final Set<String> guessedCategoryNames = new HashSet<>();
    private final Set<String> guessedWords = new HashSet<>();
    private final List<List<String>> proposalHistory = new ArrayList<>();

    public PlayerGameState(int gameId, String username, int maxMistakes) {
        this.gameId = gameId;
        this.username = username;
        this.maxMistakes = maxMistakes;
    }

    public PlayerGameState(int gameId, int maxMistakes) {
        this(gameId, null, maxMistakes);
    }

    public PlayerGameState(String username, int maxMistakes) {
        this(0, username, maxMistakes);
    }

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
     * Calcola il punteggio dinamico in base alle regole ufficiali:
     * - Bonus per gruppi indovinati: 1->+6, 2->+12, 3->+18
     * - Penalità: -4 per ogni errore commesso
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

    public boolean isGameOver() {
        return status != Status.IN_PROGRESS;
    }

    public Set<String> getGuessedCategoryNames() {
        return guessedCategoryNames;
    }

    public Set<String> getGuessedWords() {
        return guessedWords;
    }

    public int getGuessedCategoriesCount() {
        return guessedCategoryNames.size();
    }

    public boolean isWordAlreadyGuessed(String word) {
        return guessedWords.contains(word.trim().toLowerCase());
    }

    /**
     * Aggiunge una categoria indovinata. 
     * Raggiunti 3 gruppi indovinati, il giocatore vince la partita.
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
     * Incrementa il contatore degli errori.
     * Raggiunto il limite di maxMistakes (4), la partita è persa.
     */
    public void incrementMistakes() {
        this.mistakes++;
        if (this.mistakes >= maxMistakes) {
            this.status = Status.LOST;
        }
    }

    public void addProposalToHistory(List<String> proposal) {
        this.proposalHistory.add(new ArrayList<>(proposal));
    }

    public List<List<String>> getProposalHistory() {
        return proposalHistory;
    }
}