import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Gestisce lo stato della sessione di rete associata a un singolo client connesso.
 * Mantiene i buffer I/O per la gestione dei flussi TCP (Java NIO)
 * e i dati di stato condivisi in modo thread-safe.
 */
public class Session {
    
    /**
     * Buffer primario per la lettura di byte dal SocketChannel (gestito dal thread del Selector).
     */
    public final ByteBuffer buffer = ByteBuffer.allocate(8192);

    /**
     * Accumulatore di byte grezzi per la ricostruzione dei messaggi TCP completi.
     * Utilizzato al posto di StringBuilder per prevenire la corruzione di caratteri UTF-8 multi-byte.
     */
    public final ByteArrayOutputStream rawMessageBuffer = new ByteArrayOutputStream();

    // Dati volatile condivisi per garantire visibilità immediata tra i thread del ThreadPool
    private volatile int currentGameId;
    private volatile String username = null;
    private volatile int udpPort = -1;
    private volatile InetAddress clientAddress = null;

    /**
     * Costruttore della sessione client. Inizializza lo stato della sessione con un gameId iniziale.
     */
    public Session(int initialGameId, int maxMistakes) {
        this.currentGameId = initialGameId;
    }

    /**
     * Verifica se l'utente è autenticato. Restituisce true se l'utente è autenticato, false altrimenti.
     */
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