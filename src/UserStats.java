public class UserStats {
    private int score = 0;
    private int gamesPlayed = 0;
    private int gamesWon = 0;
    private int currentStreak = 0;
    private int bestStreak = 0;

    public synchronized void recordWin(int pointsGained) {
        this.gamesPlayed++;
        this.gamesWon++;
        this.score += pointsGained;
        this.currentStreak++;
        if (this.currentStreak > this.bestStreak) {
            this.bestStreak = this.currentStreak;
        }
    }

    public synchronized void recordLoss() {
        this.gamesPlayed++;
        this.currentStreak = 0;
    }

    // --- Getters ---
    public int getScore() { return score; }
    public int getGamesPlayed() { return gamesPlayed; }
    public int getGamesWon() { return gamesWon; }
    public int getCurrentStreak() { return currentStreak; }
    public int getBestStreak() { return bestStreak; }
    
    public double getWinRate() {
        return gamesPlayed == 0 ? 0.0 : ((double) gamesWon / gamesPlayed) * 100;
    }
}