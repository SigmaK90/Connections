import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Scanner;

/**
 * Gestore dell'interfaccia utente (CLI) del client di gioco Connections.
 * Gestisce la connessione TCP principale per l'invio delle richieste e un thread dedicato
 * all'ascolto delle notifiche asincrone inviate dal server via UDP Datagram.
 */
public class ClientMain {

    private final String host;
    private final int port;
    private final int preferredUdpPort;
    private int actualUdpPort;
    private final Gson prettyGson;
    private DatagramSocket udpSocket;

    /**
     * Costruisce un'istanza del client con i parametri di rete specificati.
     */
    public ClientMain(String host, int port, int preferredUdpPort) {
        this.host = host;
        this.port = port;
        this.preferredUdpPort = preferredUdpPort;
        this.prettyGson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Avvia la connessione al server, il thread di ascolto UDP e il ciclo principale
     * dell'interfaccia utente interattiva da riga di comando.
     */
    public void start() {
        startUdpNotificationListener();

        try (SocketChannel socketChannel = SocketChannel.open()) {
            socketChannel.configureBlocking(true);
            socketChannel.connect(new InetSocketAddress(host, port));

            System.out.println("[CLIENT] Connesso al server TCP " + host + ":" + port);
            System.out.println("[CLIENT] In ascolto per notifiche UDP sulla porta " + actualUdpPort);
            System.out.println("[CLIENT] Usa il menu sottostante per interagire con il server.");

            @SuppressWarnings("resource")
            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.println("\n--- MENU CONNECTIONS ---");
                System.out.println("1. Registrazione (register)");
                System.out.println("2. Login (login)");
                System.out.println("3. Aggiorna Credenziali (updateCredentials)");
                System.out.println("4. Invia Proposta (submitProposal)");
                System.out.println("5. Info Partita (requestGameInfo)");
                System.out.println("6. Statistiche Partita (requestGameStats)");
                System.out.println("7. Classifica Globale (requestLeaderboard)");
                System.out.println("8. Statistiche Personali (requestPlayerStats)");
                System.out.println("9. Logout (logout)");
                System.out.println("0. Esci dal Client");
                System.out.print("> ");
                
                if (!scanner.hasNextLine()) break;
                String choice = scanner.nextLine().trim();

                if (choice.equals("0")) {
                    System.out.println("[CLIENT] Disconnessione in corso...");
                    break;
                }

                if (choice.isEmpty()) continue;

                JsonObject jsonReq = new JsonObject();
                try {
                    switch (choice) {
                        case "1":
                            jsonReq.addProperty("operation", "register");
                            System.out.print("Username: ");
                            jsonReq.addProperty("name", scanner.nextLine().trim());
                            System.out.print("Password: ");
                            jsonReq.addProperty("psw", scanner.nextLine().trim());
                            break;
                        case "2":
                            jsonReq.addProperty("operation", "login");
                            System.out.print("Username: ");
                            jsonReq.addProperty("username", scanner.nextLine().trim());
                            System.out.print("Password: ");
                            jsonReq.addProperty("psw", scanner.nextLine().trim());
                            jsonReq.addProperty("udpPort", actualUdpPort);
                            break;
                        case "3":
                            jsonReq.addProperty("operation", "updateCredentials");
                            System.out.print("Vecchio Username: ");
                            jsonReq.addProperty("oldName", scanner.nextLine().trim());
                            System.out.print("Vecchia Password: ");
                            jsonReq.addProperty("oldPsw", scanner.nextLine().trim());
                            System.out.print("Nuovo Username (lascia vuoto per non cambiare): ");
                            String newName = scanner.nextLine().trim();
                            if (!newName.isEmpty()) jsonReq.addProperty("newName", newName);
                            System.out.print("Nuova Password (lascia vuoto per non cambiare): ");
                            String newPsw = scanner.nextLine().trim();
                            if (!newPsw.isEmpty()) jsonReq.addProperty("newPsw", newPsw);
                            break;
                        case "4":
                            jsonReq.addProperty("operation", "submitProposal");
                            System.out.println("Inserisci 4 parole separate da spazio:");
                            String[] words = scanner.nextLine().trim().split("\\s+");
                            com.google.gson.JsonArray jsonArray = new com.google.gson.JsonArray();
                            for (String w : words) {
                                if (!w.isEmpty()) jsonArray.add(w);
                            }
                            jsonReq.add("words", jsonArray);
                            break;
                        case "5":
                            jsonReq.addProperty("operation", "requestGameInfo");
                            System.out.print("ID Partita (lascia vuoto per la partita corrente): ");
                            String gIdInfo = scanner.nextLine().trim();
                            if (!gIdInfo.isEmpty()) jsonReq.addProperty("gameId", Integer.parseInt(gIdInfo));
                            break;
                        case "6":
                            jsonReq.addProperty("operation", "requestGameStats");
                            System.out.print("ID Partita (lascia vuoto per la partita corrente): ");
                            String gIdStats = scanner.nextLine().trim();
                            if (!gIdStats.isEmpty()) jsonReq.addProperty("gameId", Integer.parseInt(gIdStats));
                            break;
                        case "7":
                            jsonReq.addProperty("operation", "requestLeaderboard");
                            System.out.print("Nome giocatore per ranking relativo (lascia vuoto per omettere): ");
                            String pName = scanner.nextLine().trim();
                            if (!pName.isEmpty()) jsonReq.addProperty("playerName", pName);
                            System.out.print("Mostra top K giocatori (lascia vuoto per tutti): ");
                            String topK = scanner.nextLine().trim();
                            if (!topK.isEmpty()) jsonReq.addProperty("topPlayers", Integer.parseInt(topK));
                            break;
                        case "8":
                            jsonReq.addProperty("operation", "requestPlayerStats");
                            break;
                        case "9":
                            jsonReq.addProperty("operation", "logout");
                            break;
                        default:
                            System.out.println("[CLIENT] Scelta non valida.");
                            continue;
                    }

                    boolean continueLoop = sendAndReceive(socketChannel, jsonReq.toString());

                    if (choice.equals("9")) {
                        System.out.println("[CLIENT] Logout completato. Chiusura del client.");
                        break;
                    }

                    if (!continueLoop) break;

                } catch (Exception e) {
                    System.err.println("[ERRORE CLIENT] Input non valido: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("[CLIENT] Errore di connessione: " + e.getMessage());
        } finally {
            closeUdpSocket();
            System.out.println("[CLIENT] Client terminato.");
        }
    }

    /**
     * Inizializza il socket UDP per le notifiche e avvia un thread daemon per l'ascolto continuo.
     */
    private void startUdpNotificationListener() {
        try {
            try {
                udpSocket = new DatagramSocket(preferredUdpPort);
            } catch (SocketException e) {
                udpSocket = new DatagramSocket(0);
            }
            actualUdpPort = udpSocket.getLocalPort();
        } catch (SocketException e) {
            System.err.println("[UDP CLIENT] Impossibile inizializzare la socket UDP: " + e.getMessage());
            return;
        }

        Thread udpThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                while (!udpSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);
                    String rawNotification = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                    System.out.println("\n\n[NOTIFICA ASINCRONA UDP]:");
                    try {
                        JsonObject parsed = JsonParser.parseString(rawNotification).getAsJsonObject();
                        System.out.println(prettyGson.toJson(parsed));
                    } catch (Exception e) {
                        System.out.println(rawNotification);
                    }
                    System.out.print("> ");
                }
            } catch (SocketException e) {
                // Socket chiusa durante la disconnessione pulita
            } catch (IOException e) {
                System.err.println("[UDP CLIENT] Errore socket UDP: " + e.getMessage());
            }
        });

        udpThread.setDaemon(true);
        udpThread.start();
    }

    /**
     * Chiude in modo sicuro la socket UDP se attiva.
     */
    private void closeUdpSocket() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }

    /**
     * Invia una richiesta JSON al server tramite canale TCP e attende la risposta terminata da '\n'.
     */
    private boolean sendAndReceive(SocketChannel socketChannel, String jsonPayload) throws IOException {
        String msg = jsonPayload + "\n";
        socketChannel.write(ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8)));

        StringBuilder responseBuilder = new StringBuilder();
        ByteBuffer readBuffer = ByteBuffer.allocate(8192);

        while (true) {
            readBuffer.clear();
            int bytesRead = socketChannel.read(readBuffer);
            if (bytesRead <= 0) {
                if (bytesRead == -1) {
                    System.out.println("[CLIENT] Il server ha chiuso la connessione.");
                    return false;
                }
                break;
            }

            readBuffer.flip();
            String chunk = StandardCharsets.UTF_8.decode(readBuffer).toString();
            responseBuilder.append(chunk);

            if (responseBuilder.indexOf("\n") != -1) {
                break;
            }
        }

        String rawResponse = responseBuilder.toString().trim();
        try {
            JsonObject formattedJson = JsonParser.parseString(rawResponse).getAsJsonObject();
            System.out.println("[RISPOSTA SERVER]:\n" + prettyGson.toJson(formattedJson));
        } catch (Exception e) {
            System.out.println("[RISPOSTA RAW]: " + rawResponse);
        }

        return true;
    }

    /**
     * Metodo di avvio che carica i parametri di rete dal file client.properties ed esegue il client.
     */
    public static void main(String[] args) {
        Properties prop = new Properties();
        String host = "127.0.0.1";
        int port = 8080;
        int udpPort = 9090;

        String[] configPaths = {"config/client.properties", "../config/client.properties", "client.properties"};
        InputStream input = null;

        for (String path : configPaths) {
            try {
                File f = new File(path);
                if (f.exists()) {
                    input = new FileInputStream(f);
                    System.out.println("[CLIENT] Caricata configurazione da: " + path);
                    break;
                }
            } catch (FileNotFoundException ignored) {}
        }

        if (input != null) {
            try {
                prop.load(input);
                host = prop.getProperty("server.host", host);
                port = Integer.parseInt(prop.getProperty("server.port", String.valueOf(port)));
                udpPort = Integer.parseInt(prop.getProperty("client.udp.port", String.valueOf(udpPort)));
                input.close();
            } catch (IOException | NumberFormatException e) {
                System.out.println("[CLIENT] Errore lettura client.properties, uso default.");
            }
        } else {
            System.out.println("[CLIENT] Nessun file client.properties trovato, uso default.");
        }

        ClientMain client = new ClientMain(host, port, udpPort);
        client.start();
    }
}