import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class Category {

    @SerializedName(value = "theme", alternate = {"category"})
    private String theme;

    private List<String> words;

    public Category() {}

    public Category(String theme, List<String> words) {
        this.theme = theme;
        this.words = words;
    }

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
     * Verifica se la lista di 4 parole inoltrata corrisponde esattamente a questa categoria.
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
     * Conta quante parole della proposta appartengono a questa categoria.
     * Utile per rilevare l'esito "One Away" (3 parole su 4).
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