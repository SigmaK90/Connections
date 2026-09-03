import java.util.HashMap;
import java.util.Map;

/**
 * Rappresenta un utente del sistema, gestendone le credenziali di accesso
 * e lo storico completo delle statistiche di gioco (stile NYT Connections).
 */
public class User {
    private String username;
    private String passwordHash;
    private int score;
    private int gamesPlayed;
    private int gamesWon;

    // Campi statistici aggiuntivi (NYT Connections style)
    private int currentStreak;
    private int maxStreak;
    private int perfectPuzzles; // Partite vinte con 0 errori
    private Map<String, Integer> mistakeHistogram; // Frequenza degli errori (0, 1, 2, 3, 4, DNF)

    /**
     * Costruttore completo. Inizializza l'istogramma degli errori.
     */
    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.score = 0;
        this.gamesPlayed = 0;
        this.gamesWon = 0;
        this.currentStreak = 0;
        this.maxStreak = 0;
        this.perfectPuzzles = 0;
        
        this.mistakeHistogram = new HashMap<>();
        for (int i = 0; i <= 4; i++) {
            this.mistakeHistogram.put(String.valueOf(i), 0);
        }
        this.mistakeHistogram.put("DNF", 0);
    }

    /**
     * Aggiorna le statistiche dell'utente in modo thread-safe, considerando vittorie, sconfitte, errori e timeout.
     */
    public synchronized void recordGameResult(int matchScore, boolean won, int mistakes, boolean timedOut) {
        this.score += matchScore;
        this.gamesPlayed++;

        if (timedOut) {
            this.mistakeHistogram.put("DNF", this.mistakeHistogram.getOrDefault("DNF", 0) + 1);
            this.currentStreak = 0;
        } else {
            int safeMistakes = Math.min(Math.max(0, mistakes), 4);
            String key = String.valueOf(safeMistakes);
            this.mistakeHistogram.put(key, this.mistakeHistogram.getOrDefault(key, 0) + 1);

            if (won) {
                this.gamesWon++;
                this.currentStreak++;
                if (this.currentStreak > this.maxStreak) {
                    this.maxStreak = this.currentStreak;
                }
                if (safeMistakes == 0) {
                    this.perfectPuzzles++;
                }
            } else {
                this.currentStreak = 0;
            }
        }
    }

    /**
     * Overload di recordGameResult per gestire partite senza timeout.
     */
    public synchronized void recordGameResult(int matchScore, boolean won, int mistakes) {
        recordGameResult(matchScore, won, mistakes, false);
    }

    // Getters e Setters base
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public int getScore() { return score; }
    public int getGamesPlayed() { return gamesPlayed; }
    public int getGamesWon() { return gamesWon; }
    public int getCurrentStreak() { return currentStreak; }
    public int getMaxStreak() { return maxStreak; }
    public int getPerfectPuzzles() { return perfectPuzzles; }

    /**
     * Calcola la percentuale di partite vinte rispetto al totale delle partite giocate.
     */
    public double getWinRate() {
        if (gamesPlayed == 0) return 0.0;
        return ((double) gamesWon / gamesPlayed) * 100.0;
    }

    /**
     * Calcola la percentuale di partite perse rispetto al totale delle partite giocate.
     */
    public double getLossRate() {
        if (gamesPlayed == 0) return 0.0;
        int gamesLost = gamesPlayed - gamesWon;
        return ((double) gamesLost / gamesPlayed) * 100.0;
    }

    /**
     * Restituisce una copia dell'istogramma degli errori per evitare modifiche esterne.
     */
    public Map<String, Integer> getMistakeHistogram() { return new HashMap<>(mistakeHistogram); }
}