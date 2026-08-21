import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Puzzle {

    @SerializedName("gameId")
    private int gameId;

    @SerializedName("groups")
    private List<Category> groups;

    public Puzzle() {}

    public Puzzle(int gameId, List<Category> groups) {
        this.gameId = gameId;
        this.groups = groups;
    }

    public int getGameId() {
        return gameId;
    }

    public void setGameId(int gameId) {
        this.gameId = gameId;
    }

    public List<Category> getGroups() {
        return groups;
    }

    public void setGroups(List<Category> groups) {
        this.groups = groups;
    }

    /**
     * Raccoglie tutte le 16 parole dei 4 gruppi e le restituisce mescolate casualmente.
     */
    public List<String> getAllWordsShuffled() {
        List<String> allWords = new ArrayList<>();
        if (groups != null) {
            for (Category group : groups) {
                if (group.getWords() != null) {
                    allWords.addAll(group.getWords());
                }
            }
        }
        Collections.shuffle(allWords);
        return allWords;
    }
}