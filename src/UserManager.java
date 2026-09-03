import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestisce l'autenticazione, la registrazione, la persistenza su file JSON
 * e le statistiche dei giocatori, mantenendo le associazioni con le porte UDP per le notifiche.
 */
public class UserManager {

    private final String filePath;
    private final Gson gson;
    
    // Mappa thread-safe per memorizzare gli utenti registrati e le loro credenziali
    private final Map<String, User> users = new ConcurrentHashMap<>();

    // Mappa thread-safe per memorizzare le associazioni tra username e porte UDP
    private final Map<String, Integer> udpPorts = new ConcurrentHashMap<>();

    // Lock per sincronizzare l'accesso al file JSON durante le operazioni di lettura/scrittura
    private final Object fileLock = new Object();

    /**
     * Costruttore dell'UserManager. Inizializza il percorso del file JSON e carica gli utenti esistenti.
     */
    public UserManager(String filePath) {
        this.filePath = filePath;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadUsers();
    }

    /**
     * Carica in modo thread-safe la mappa degli utenti dal file JSON.
     */
    private void loadUsers() {
        synchronized (fileLock) {
            File file = new File(filePath);
            if (!file.exists()) return;

            try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, User>>() {}.getType();
                Map<String, User> loadedUsers = gson.fromJson(reader, type);
                if (loadedUsers != null) {
                    users.clear();
                    users.putAll(loadedUsers);
                }
            } catch (IOException e) {
                System.err.println("[USER_MANAGER] Errore caricamento utenti: " + e.getMessage());
            }
        }
    }

    /**
     * Salva in modo thread-safe lo stato aggiornato degli utenti sul file JSON.
     */
    private void saveUsers() {
        synchronized (fileLock) {
            try (Writer writer = new FileWriter(filePath, StandardCharsets.UTF_8)) {
                gson.toJson(users, writer);
            } catch (IOException e) {
                System.err.println("[USER_MANAGER] Errore salvataggio utenti: " + e.getMessage());
            }
        }
    }

    /**
     * Registra un nuovo utente calcolando l'hash della password e salvando lo stato su file.
     */
    public boolean register(String username, String plainPassword) {
        if (username == null || plainPassword == null || username.trim().isEmpty() || plainPassword.trim().isEmpty()) {
            return false;
        }

        String hash = hashPassword(plainPassword);
        if (hash == null) return false;

        synchronized (fileLock) {
            String trimmed = username.trim();
            if (users.containsKey(trimmed)) {
                return false;
            }
            User newUser = new User(trimmed, hash);
            users.put(trimmed, newUser);
            saveUsers();
            return true;
        }
    }

    /**
     * Autentica un utente verificando la corrispondenza dell'hash della password.
     * 
     */
    public boolean login(String username, String plainPassword) {
        if (username == null || plainPassword == null) return false;

        synchronized (fileLock) {
            User user = users.get(username.trim());
            if (user == null) return false;

            String hash = hashPassword(plainPassword);
            return hash != null && user.getPasswordHash().equals(hash);
        }
    }

    /**
     * Associa una porta UDP attiva ad un utente per il servizio di notifiche datagram.
     */
    public void registerUdpPort(String username, int port) {
        if (username != null && port > 0 && port <= 65535) {
            udpPorts.put(username.trim(), port);
        }
    }

    /**
     * Rimuove la porta UDP associata all'utente al momento del logout.
     */
    public void unregisterUdpPort(String username) {
        if (username != null) {
            udpPorts.remove(username.trim());
        }
    }

    /**
     * Recupera la porta UDP registrata per un dato utente.
     */
    public Integer getUdpPort(String username) {
        if (username == null) return null;
        return udpPorts.get(username.trim());
    }

    /**
     * Restituisce una copia della mappa contenente le porte UDP di tutti gli utenti attualmente registrati.
     */
    public Map<String, Integer> getAllUdpPorts() {
        return new ConcurrentHashMap<>(udpPorts);
    }

    /**
     * Aggiorna le credenziali di un utente (username e/o password) mantenendo la coerenza dei dati e delle porte UDP.
     * Metodo thread-safe.
     */
    public boolean updateCredentials(String oldName, String oldPsw, String newName, String newPsw) {
        if (!login(oldName, oldPsw)) return false;

        synchronized (fileLock) {
            User user = users.get(oldName);
            if (user == null) return false;

            String trimmedNewName = (newName != null) ? newName.trim() : null;

            if (trimmedNewName != null && !trimmedNewName.isEmpty() && !trimmedNewName.equals(oldName)) {
                if (users.containsKey(trimmedNewName)) return false;
                
                users.remove(oldName);
                user.setUsername(trimmedNewName);
                users.put(trimmedNewName, user);

                Integer port = udpPorts.remove(oldName);
                if (port != null) {
                    udpPorts.put(trimmedNewName, port);
                }
            }

            if (newPsw != null && !newPsw.trim().isEmpty()) {
                String newHash = hashPassword(newPsw);
                if (newHash != null) {
                    user.setPasswordHash(newHash);
                }
            }

            saveUsers();
            return true;
        }
    }

    /**
     * Registra il completamento di una partita aggiornando le statistiche dell'utente e persistendo le modifiche.
     * Metodo thread-safe.
     */
    public void recordCompletedGame(String username, int matchScore, boolean won, int mistakes, boolean timedOut) {
        if (username == null) return;
        synchronized (fileLock) {
            User user = users.get(username);
            if (user != null) {
                user.recordGameResult(matchScore, won, mistakes, timedOut);
                saveUsers();
            }
        }
    }

    /**
     * Overload per registrare una partita conclusa senza specificare la condizione di timeout.
     */
    public void recordCompletedGame(String username, int matchScore, boolean won, int mistakes) {
        recordCompletedGame(username, matchScore, won, mistakes, false);
    }

    /**
     * Costruisce e restituisce una mappa contenente tutte le statistiche dettagliate di un utente.
     */
    public Map<String, Object> getUserStats(String username) {
        if (username == null) return null;
        synchronized (fileLock) {
            User user = users.get(username);
            if (user == null) return null;

            Map<String, Object> stats = new HashMap<>();
            int played = user.getGamesPlayed();
            int won = user.getGamesWon();
            int lost = played - won;

            stats.put("username", user.getUsername());
            stats.put("score", user.getScore());
            stats.put("puzzlesCompleted", played);
            stats.put("gamesWon", won);
            stats.put("gamesLost", lost);
            stats.put("winRate", user.getWinRate());
            stats.put("lossRate", user.getLossRate());
            stats.put("currentStreak", user.getCurrentStreak());
            stats.put("maxStreak", user.getMaxStreak());
            stats.put("perfectPuzzles", user.getPerfectPuzzles());
            stats.put("mistakeHistogram", user.getMistakeHistogram());
            return stats;
        }
    }

    /**
     * Calcola la classifica globale di tutti gli utenti registrati ordinata per punteggio decrescente.
     */
    public List<Map<String, Object>> getLeaderboard() {
        synchronized (fileLock) {
            List<User> sortedUsers = new ArrayList<>(users.values());
            sortedUsers.sort((u1, u2) -> Integer.compare(u2.getScore(), u1.getScore()));

            List<Map<String, Object>> result = new ArrayList<>();
            int rank = 1;
            for (User u : sortedUsers) {
                Map<String, Object> map = new HashMap<>();
                map.put("rank", rank++);
                map.put("username", u.getUsername());
                map.put("score", u.getScore());
                map.put("gamesWon", u.getGamesWon());
                map.put("puzzlesCompleted", u.getGamesPlayed());
                result.add(map);
            }
            return result;
        }
    }

    /**
     * Restituisce la classifica filtrata per i primi K giocatori ed eventualmente la posizione specifica dell'utente richiesto.
     */
    public Map<String, Object> getLeaderboard(String targetPlayer, Integer topK) {
        List<Map<String, Object>> fullBoard = getLeaderboard();
        Map<String, Object> response = new HashMap<>();

        if (topK != null && topK > 0 && topK < fullBoard.size()) {
            response.put("top", fullBoard.subList(0, topK));
        } else {
            response.put("top", fullBoard);
        }

        if (targetPlayer != null && !targetPlayer.trim().isEmpty()) {
            Map<String, Object> playerRank = fullBoard.stream()
                    .filter(m -> targetPlayer.trim().equalsIgnoreCase((String) m.get("username")))
                    .findFirst()
                    .orElse(null);
            response.put("player", playerRank);
        }

        return response;
    }

    /**
     * Calcola l'hash SHA-256 di una password in chiaro e restituisce la rappresentazione esadecimale.
     * In caso di errore, restituisce null.
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("[USER_MANAGER] SHA-256 non disponibile!");
            return null;
        }
    }
}