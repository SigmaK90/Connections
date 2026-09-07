import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server di rete principale basato su Java NIO e sul pattern Reactor/Worker.
 * Gestisce le connessioni TCP dei client tramite un Selector e delega l'elaborazione
 * delle richieste a un pool di thread worker. Gestisce inoltre l'invio di notifiche UDP in broadcast.
 */
public class NioServerMain implements Runnable {

    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private DatagramSocket udpSocket;
    private volatile boolean running = true;

    private final Gson gson;
    private final UserManager userManager;
    private final GameManager gameManager;
    private final RequestHandler requestHandler;

    private final ExecutorService threadPool;

    // Set thread-safe delle sessioni attive per broadcast UDP in sicurezza.
    private final Set<Session> activeSessions = ConcurrentHashMap.newKeySet();

    /**
     * Inizializza il server caricando le configurazioni, istanziando i gestori di gioco/utenti
     * e registrando i callback per le notifiche UDP di inizio/pausa partita.
     */
    public NioServerMain(int port, String puzzleFilePath, String historyFilePath, long gameDurationMs, long pauseDurationMs, int maxMistakes, int threadPoolSize) {
        this.port = port;
        this.gson = new Gson();
        this.userManager = new UserManager("users.json");

        this.gameManager = new GameManager(puzzleFilePath, historyFilePath, gameDurationMs, pauseDurationMs, maxMistakes, userManager);
        this.requestHandler = new RequestHandler(userManager, gameManager);
        this.threadPool = Executors.newFixedThreadPool(threadPoolSize);

        // Callback per notifiche UDP inviate dal GameManager in coincidenza con gli eventi di gioco
        this.gameManager.setOnGameStartCallback((gameId, payload) ->
            broadcastUdpNotification(payload)
        );

        this.gameManager.setOnPauseStartCallback((gameId, payload) ->
            broadcastUdpNotification(payload)
        );
    }

    /**
     * Esegue il ciclo principale del Selector Java NIO per intercettare ed elaborare gli eventi I/O.
     */
    @Override
    public void run() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            udpSocket = new DatagramSocket();

            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("[SERVER] In ascolto sulla porta " + port + "... ");

            while (running) {
                if (selector.select(1000) == 0) {
                    continue;
                }

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        acceptConnection(key);
                    } else if (key.isReadable()) {
                        readFromClient(key);
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("[SERVER FATAL ERROR] Errore critico nel server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    /**
     * Accetta una nuova connessione TCP in arrivo, la imposta come non bloccante
     * e la registra sul Selector in ascolto per eventi di lettura (OP_READ).
     */
    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = server.accept();
        clientChannel.configureBlocking(false);

        Session session = new Session(0, 4);

        InetSocketAddress remoteAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
        session.setClientAddress(remoteAddress.getAddress());

        activeSessions.add(session);
        clientChannel.register(selector, SelectionKey.OP_READ, session);

        System.out.println("[SERVER] Nuova connessione da: " + remoteAddress);
    }

    /**
     * Legge i byte in arrivo dal canale client e li accumula. Quando viene rilevato il carattere
     * di terminazione '\n', il messaggio JSON viene inviato al thread pool per l'elaborazione.
     */
    private void readFromClient(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        Session session = (Session) key.attachment();
        ByteBuffer buffer = session.buffer;

        try {
            int bytesRead = clientChannel.read(buffer);

            if (bytesRead == -1) {
                closeClientConnection(key, clientChannel, session, "Client disconnesso normalmente.");
                return;
            }

            buffer.flip();

            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                if (b == '\n') {
                    // Decodifica il messaggio completo da byte grezzi a stringa UTF-8
                    byte[] rawBytes = session.rawMessageBuffer.toByteArray();
                    session.rawMessageBuffer.reset();
                    String jsonRequest = new String(rawBytes, StandardCharsets.UTF_8).trim();

                    if (!jsonRequest.isEmpty()) {
                        System.out.println("[SERVER] Ricevuto da " + clientChannel.getRemoteAddress() + ": " + jsonRequest);

                        if (isLogoutRequest(jsonRequest)) {
                            // Disattiva letture successive prima di delegare il logout al pool
                            key.interestOps(0);
                            threadPool.execute(() -> {
                                processAndRespond(clientChannel, session, jsonRequest);
                                closeClientConnection(key, clientChannel, session, "Logout eseguito con successo.");
                            });
                            buffer.clear();
                            return;
                        }

                        threadPool.execute(() -> processAndRespond(clientChannel, session, jsonRequest));
                    }
                } else if (b != '\r') {
                    // Accumula byte grezzi (non char) per preservare UTF-8 multi-byte
                    session.rawMessageBuffer.write(b);
                }
            }

            buffer.clear();

        } catch (IOException e) {
            closeClientConnection(key, clientChannel, session, "Connessione interrotta in modo anomalo.");
        }
    }

    /**
     * Elabora la richiesta JSON tramite RequestHandler e scrive la risposta sul canale del client.
     * Metodo eseguito dai thread del ThreadPool.
     */
    private void processAndRespond(SocketChannel clientChannel, Session session, String jsonRequest) {
        Response response;

        try {
            Request request = gson.fromJson(jsonRequest, Request.class);

            if (request == null || request.getOperation() == null) {
                response = Response.error("Richiesta malformata: operazione mancante.");
            } else {
                response = requestHandler.handle(request, session);
            }

        } catch (JsonSyntaxException e) {
            response = Response.error("Formato JSON non valido.");
        }

        String jsonResponse = gson.toJson(response) + "\n";

        synchronized (clientChannel) {
            try {
                if (clientChannel.isOpen()) {
                    ByteBuffer writeBuffer = ByteBuffer.wrap(jsonResponse.getBytes(StandardCharsets.UTF_8));
                    while (writeBuffer.hasRemaining()) {
                        int written = clientChannel.write(writeBuffer);
                        if (written == 0) {
                            // Previene busy-waiting su socket buffer temporaneamente pieno
                            Thread.sleep(1);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("[SERVER] Errore nell'invio della risposta: " + e.getMessage());
            }
        }
    }

    /**
     * Chiude in sicurezza il canale client, annulla la chiave del Selector e ripulisce la sessione.
     */
    private void closeClientConnection(SelectionKey key, SocketChannel clientChannel, Session session, String reason) {
        try {
            String clientInfo = (clientChannel != null && clientChannel.isOpen())
                    ? clientChannel.getRemoteAddress().toString()
                    : "Sconosciuto";

            if (session != null) {
                activeSessions.remove(session);
                if (session.isLoggedIn()) {
                    System.out.println("[SERVER] Cleanup sessione per l'utente: " + session.getUsername());
                    userManager.unregisterUdpPort(session.getUsername());
                    session.setUsername(null);
                }
            }

            if (key != null) {
                key.cancel();
            }

            if (clientChannel != null && clientChannel.isOpen()) {
                clientChannel.close();
            }

            System.out.println("[SERVER] Connessione chiusa (" + clientInfo + "): " + reason);

        } catch (IOException e) {
            System.err.println("[SERVER] Errore durante la chiusura del canale: " + e.getMessage());
        }
    }

    /**
     * Verifica se la stringa JSON ricevuta rappresenta una richiesta di logout.
     */
    private boolean isLogoutRequest(String json) {
        try {
            Request r = gson.fromJson(json, Request.class);
            return r != null && "logout".equalsIgnoreCase(r.getOperation());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Invia una notifica UDP Datagram a una specifica sessione utente.
     */
    public void sendUdpNotification(Session session, String message) {
        if (session == null || !session.isLoggedIn() || session.getUdpPort() <= 0 || session.getClientAddress() == null) {
            return;
        }

        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, session.getClientAddress(), session.getUdpPort());
            udpSocket.send(packet);
        } catch (IOException e) {
            System.err.println("[UDP SERVER] Errore invio notifica: " + e.getMessage());
        }
    }

    /**
     * Invia una notifica UDP Datagram in broadcast a tutte le sessioni attualmente connesse e autenticate.
     */
    public void broadcastUdpNotification(String message) {
        if (udpSocket == null || udpSocket.isClosed()) return;

        // Itera sul set thread-safe activeSessions (evita ConcurrentModificationException)
        for (Session session : activeSessions) {
            sendUdpNotification(session, message);
        }
    }

    /**
     * Interrompe il ciclo del server e risveglia il Selector arrestando i sottosistemi.
     */
    public void stop() {
        this.running = false;
        if (gameManager != null) {
            gameManager.stop();
        }
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Esegue l'arresto pulito delle risorse di rete, chiude i canali e fa shutdown del ThreadPool.
     */
    private void shutdown() {
        try {
            if (gameManager != null) {
                gameManager.stop();
            }
            threadPool.shutdown();

            if (selector != null && selector.isOpen()) {
                // Snapshot delle chiavi per evitare ConcurrentModificationException
                List<SelectionKey> keysSnapshot = new ArrayList<>(selector.keys());
                for (SelectionKey key : keysSnapshot) {
                    if (key.channel() instanceof SocketChannel) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        Session session = (Session) key.attachment();
                        closeClientConnection(key, channel, session, "Shutdown del Server.");
                    }
                }
                selector.close();
            }

            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            if (serverChannel != null) {
                serverChannel.close();
            }

            System.out.println("[SERVER] Server ed eventuali risorse residue arrestati correttamente.");
        } catch (IOException e) {
            System.err.println("[SERVER] Errore durante lo shutdown: " + e.getMessage());
        }
    }

    /**
     * Metodo main che legge il file di configurazione server.properties e avvia il server NIO.
     */
    public static void main(String[] args) {
        Properties prop = new Properties();
        int port = 8080;
        String puzzleFile = "Connections_Data.json";
        String historyFile = "game_history.json";
        long gameDurationMs = 180000L;
        long pauseDurationMs = 30000L;
        int maxMistakes = 4;
        int threadPoolSize = 10;

        String[] configPaths = {"config/server.properties", "../config/server.properties", "server.properties"};
        InputStream input = null;

        for (String path : configPaths) {
            try {
                File f = new File(path);
                if (f.exists()) {
                    input = new FileInputStream(f);
                    System.out.println("[SERVER] Caricata configurazione da: " + path);
                    break;
                }
            } catch (FileNotFoundException ignored) {
            }
        }

        if (input != null) {
            try {
                prop.load(input);
                port = Integer.parseInt(prop.getProperty("server.port", String.valueOf(port)));
                puzzleFile = prop.getProperty("puzzle.file.path", puzzleFile);
                historyFile = prop.getProperty("game.history.file.path", historyFile);

                String gameTimeStr = prop.getProperty("game.time.second");
                if (gameTimeStr != null) {
                    gameDurationMs = Long.parseLong(gameTimeStr.trim()) * 1000L;
                }

                String pauseTimeStr = prop.getProperty("game.pause.time.second");
                if (pauseTimeStr != null) {
                    pauseDurationMs = Long.parseLong(pauseTimeStr.trim()) * 1000L;
                }

                String mistakesStr = prop.getProperty("game.max.mistakes");
                if (mistakesStr != null) {
                    maxMistakes = Integer.parseInt(mistakesStr.trim());
                }

                String poolSizeStr = prop.getProperty("server.threadpool.size");
                if (poolSizeStr != null) {
                    threadPoolSize = Integer.parseInt(poolSizeStr.trim());
                }
                input.close();
            } catch (IOException | NumberFormatException e) {
                System.out.println("[SERVER] Errore lettura server.properties, uso default.");
            }
        } else {
            System.out.println("[SERVER] Nessun file server.properties trovato, uso default.");
        }

        // Risoluzione flessibile del percorso del dataset dei puzzle
        if (!new File(puzzleFile).exists()) {
            String[] candidatePaths = {"data/Connections_Data.json", "../data/Connections_Data.json", "Connections_Data.json"};
            for (String cp : candidatePaths) {
                if (new File(cp).exists()) {
                    puzzleFile = cp;
                    break;
                }
            }
        }

        NioServerMain server = new NioServerMain(port, puzzleFile, historyFile, gameDurationMs, pauseDurationMs, maxMistakes, threadPoolSize);
        Thread serverThread = new Thread(server);
        serverThread.start();

        try {
            serverThread.join();
        } catch (InterruptedException e) {
            System.err.println("[SERVER] Thread principale interrotto.");
        }
    }
}