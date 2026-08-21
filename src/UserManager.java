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

public class UserManager {

    private final String filePath;
    private final Gson gson;
    
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Integer> udpPorts = new ConcurrentHashMap<>();
    private final Object fileLock = new Object();

    public UserManager(String filePath) {
        this.filePath = filePath;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadUsers();
    }

    private void loadUsers() {
        synchronized (fileLock) {
            File file = new File(filePath);
            if (!file.exists()) return;

            try (Reader reader = new FileReader(file)) {
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

    private void saveUsers() {
        synchronized (fileLock) {
            try (Writer writer = new FileWriter(filePath)) {
                gson.toJson(users, writer);
            } catch (IOException e) {
                System.err.println("[USER_MANAGER] Errore salvataggio utenti: " + e.getMessage());
            }
        }
    }

    public boolean register(String username, String plainPassword) {
        if (username == null || plainPassword == null || username.trim().isEmpty() || plainPassword.trim().isEmpty()) {
            return false;
        }

        String hash = hashPassword(plainPassword);
        if (hash == null) return false;

        User newUser = new User(username.trim(), hash);

        User existing = users.putIfAbsent(username.trim(), newUser);
        if (existing == null) {
            saveUsers();
            return true;
        }
        return false;
    }

    public boolean login(String username, String plainPassword) {
        if (username == null || plainPassword == null) return false;

        User user = users.get(username.trim());
        if (user == null) return false;

        String hash = hashPassword(plainPassword);
        return hash != null && user.getPasswordHash().equals(hash);
    }

    public void registerUdpPort(String username, int port) {
        if (username != null && port > 0 && port <= 65535) {
            udpPorts.put(username.trim(), port);
        }
    }

    public void unregisterUdpPort(String username) {
        if (username != null) {
            udpPorts.remove(username.trim());
        }
    }

    public Integer getUdpPort(String username) {
        if (username == null) return null;
        return udpPorts.get(username.trim());
    }

    public Map<String, Integer> getAllUdpPorts() {
        return new HashMap<>(udpPorts);
    }

    public boolean updateCredentials(String oldName, String oldPsw, String newName, String newPsw) {
        if (!login(oldName, oldPsw)) return false;

        synchronized (this) {
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

    public void recordCompletedGame(String username, int matchScore, boolean won, int mistakes) {
        if (username == null) return;
        User user = users.get(username);
        if (user != null) {
            user.recordGameResult(matchScore, won, mistakes);
            saveUsers();
        }
    }

    public Map<String, Object> getUserStats(String username) {
        if (username == null) return null;
        User user = users.get(username);
        if (user == null) return null;

        Map<String, Object> stats = new HashMap<>();
        synchronized (user) {
            int played = user.getGamesPlayed();
            int won = user.getGamesWon();
            int lost = played - won;

            double winRate = played > 0 ? ((double) won / played) * 100 : 0.0;
            double lossRate = played > 0 ? ((double) lost / played) * 100 : 0.0;

            stats.put("username", user.getUsername());
            stats.put("score", user.getScore());
            stats.put("puzzlesCompleted", played);
            stats.put("gamesWon", won);
            stats.put("gamesLost", lost);
            stats.put("winRate", winRate);
            stats.put("lossRate", lossRate);
            stats.put("currentStreak", user.getCurrentStreak());
            stats.put("maxStreak", user.getMaxStreak());
            stats.put("perfectPuzzles", user.getPerfectPuzzles());
            stats.put("mistakeHistogram", user.getMistakeHistogram());
        }
        return stats;
    }

    public List<Map<String, Object>> getLeaderboard() {
        List<User> sortedUsers = new ArrayList<>(users.values());
        sortedUsers.sort((u1, u2) -> Integer.compare(u2.getScore(), u1.getScore()));

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (User u : sortedUsers) {
            Map<String, Object> map = new HashMap<>();
            synchronized (u) {
                map.put("rank", rank++);
                map.put("username", u.getUsername());
                map.put("score", u.getScore());
                map.put("gamesWon", u.getGamesWon());
                map.put("puzzlesCompleted", u.getGamesPlayed());
            }
            result.add(map);
        }
        return result;
    }

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