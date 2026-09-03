import java.util.List;

/**
 * Rappresenta una richiesta inviata dal client al server.
 * I campi vengono popolati automaticamente da GSON durante la deserializzazione JSON
 * in base al tipo di operazione richiesta.
 */
public class Request {

    // Identificatore del tipo di richiesta (es. "login", "register")
    private String operation;

    // Credenziali utente
    private String username;
    private String name;
    private String psw;

    // Campi per la modifica delle credenziali durante la richiesta di update
    private String oldName;
    private String oldPsw;
    private String newName;
    private String newPsw;
    
    // Lista di parole inviate dall'utente per la proposta di una soluzione
    private List<String> words;
    
    // Campi con tipi wrapper per gestire valori nulli tramite GSON
    private Integer gameId;        // Per requestGameInfo e requestGameStats
    private String playerName;     // Per requestLeaderboard
    private Integer topPlayers;    // Per requestLeaderboard
    
    // Porta UDP su cui il client è in ascolto per le notifiche asincrone
    private int udpPort;

    // Costruttore vuoto richiesto da GSON per la reflection durante la deserializzazione
    public Request() {}

    // Getter e setter per tutti i campi di Request
    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPsw() {
        return psw;
    }

    public void setPsw(String psw) {
        this.psw = psw;
    }

    public String getOldName() {
        return oldName;
    }

    public void setOldName(String oldName) {
        this.oldName = oldName;
    }

    public String getOldPsw() {
        return oldPsw;
    }

    public void setOldPsw(String oldPsw) {
        this.oldPsw = oldPsw;
    }

    public String getNewName() {
        return newName;
    }

    public void setNewName(String newName) {
        this.newName = newName;
    }

    public String getNewPsw() {
        return newPsw;
    }

    public void setNewPsw(String newPsw) {
        this.newPsw = newPsw;
    }

    public List<String> getWords() {
        return words;
    }

    public void setWords(List<String> words) {
        this.words = words;
    }

    public Integer getGameId() {
        return gameId;
    }

    public void setGameId(Integer gameId) {
        this.gameId = gameId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public Integer getTopPlayers() {
        return topPlayers;
    }

    public void setTopPlayers(Integer topPlayers) {
        this.topPlayers = topPlayers;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }
}