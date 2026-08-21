import java.util.HashMap;
import java.util.Map;

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
    private Map<Integer, Integer> mistakeHistogram; // Frequenza degli errori (0, 1, 2, 3, 4)

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
            this.mistakeHistogram.put(i, 0);
        }
    }

    public synchronized void recordGameResult(int matchScore, boolean won, int mistakes) {
        this.score += matchScore;
        this.gamesPlayed++;

        // Aggiorna Istogramma Errori
        int safeMistakes = Math.min(Math.max(0, mistakes), 4);
        this.mistakeHistogram.put(safeMistakes, this.mistakeHistogram.getOrDefault(safeMistakes, 0) + 1);

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

    // Getter e Setter
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
    public Map<Integer, Integer> getMistakeHistogram() { return new HashMap<>(mistakeHistogram); }
}