import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Rappresenta una categoria di parole di connections, deserializzate da Connections_Data.json
 */
public class Category {
    // Mappo il campo "theme" del json, ma accetto anche "category"
    @SerializedName(value = "theme", alternate = {"category"})
    private String theme;
    // Parole che appartengono alla categoria
    private List<String> words;
    // Costruttore vuoto richiesto da GSON per la reflection
    public Category() {}

    public Category(String theme, List<String> words) {
        this.theme = theme;
        this.words = words;
    }

    // Getters e setters
    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public List<String> getWords() {
        return words;
    }

    public void setWords(List<String> words) {
        this.words = words;
    }

    /**
     * Metodo che verifica se una proposta dell'utente sia una categoria ben formata ed esistente
     */
    public boolean matches(List<String> userWords) {
        if (userWords == null || userWords.size() != 4 || words == null) {
            return false;
        }

        Set<String> categoryWordsLower = new HashSet<>();
        for (String w : words) {
            categoryWordsLower.add(w.trim().toLowerCase());
        }

        for (String uw : userWords) {
            if (!categoryWordsLower.contains(uw.trim().toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Metodo che conta quante parole di una stringa corrispondono a quelle della categoria
     */
    public int countMatches(List<String> userWords) {
        if (userWords == null || words == null) {
            return 0;
        }

        Set<String> categoryWordsLower = new HashSet<>();
        for (String w : words) {
            categoryWordsLower.add(w.trim().toLowerCase());
        }

        int count = 0;
        for (String uw : userWords) {
            if (categoryWordsLower.contains(uw.trim().toLowerCase())) {
                count++;
            }
        }
        return count;
    }
}