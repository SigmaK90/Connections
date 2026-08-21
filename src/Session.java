import java.net.InetAddress;
import java.nio.ByteBuffer;

public class Session {
    
    // Buffer I/O per NIO (utilizzato dal thread del Selector)
    public final ByteBuffer buffer = ByteBuffer.allocate(1024);
    public final StringBuilder messageBuilder = new StringBuilder();

    // Dati volatile condivisi per garantire visibilità immediata tra i thread del ThreadPool
    private volatile int currentGameId;
    private volatile String username = null;
    private volatile int udpPort = -1;
    private volatile InetAddress clientAddress = null; // Aggiunto per UDP

    // Stato locale di gioco incapsulato
    public final PlayerGameState gameState;

    public Session(int initialGameId, int maxMistakes) {
        this.currentGameId = initialGameId;
        this.gameState = new PlayerGameState(initialGameId, maxMistakes);
    }

    public boolean isLoggedIn() {
        return username != null;
    }

    public InetAddress getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(InetAddress clientAddress) {
        this.clientAddress = clientAddress;
    }

    public int getCurrentGameId() {
        return currentGameId;
    }

    public void setCurrentGameId(int currentGameId) {
        this.currentGameId = currentGameId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }
}