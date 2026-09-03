import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modella una singola partita del gioco Connections.
 * Contiene l'identificativo univoco del puzzle e la lista dei quattro gruppi (categorie) di parole.
 */
public class Puzzle {

    @SerializedName("gameId")
    private int gameId;

    @SerializedName("groups")
    private List<Category> groups;

    // Campo escluso dalla serializzazione GSON; memorizza la lista casuale di tutte le parole del puzzle
    private transient List<String> cachedShuffledWords;

    /**
     * Costruttore vuoto per l'istanziazione tramite reflection della libreria GSON.
     */
    public Puzzle() {}

    /**
     * Costruttore completo per la creazione di un puzzle con ID e gruppi specificati.
     */
    public Puzzle(int gameId, List<Category> groups) {
        this.gameId = gameId;
        this.groups = groups;
    }

    // Getters e setters
    public int getGameId() {
        return gameId;
    }

    public void setGameId(int gameId) {
        this.gameId = gameId;
    }

    public List<Category> getGroups() {
        return groups;
    }

    /**
     * Imposta i gruppi del puzzle e resetta la cache delle parole mescolate.
     */
    public void setGroups(List<Category> groups) {
        this.groups = groups;
        this.cachedShuffledWords = null;
    }

    /**
     * Raccoglie tutte le parole di tutti i gruppi, le mescola casualmente al primo utilizzo
     * e restituisce una copia della lista mescolata.
     * Metodo thread-safe per gestire accessi concorrenti tra sessioni diverse.
     */
    public synchronized List<String> getAllWordsShuffled() {
        if (cachedShuffledWords == null) {
            List<String> allWords = new ArrayList<>();
            if (groups != null) {
                for (Category group : groups) {
                    if (group.getWords() != null) {
                        allWords.addAll(group.getWords());
                    }
                }
            }
            Collections.shuffle(allWords);
            this.cachedShuffledWords = Collections.unmodifiableList(allWords);
        }
        return new ArrayList<>(cachedShuffledWords);
    }
}