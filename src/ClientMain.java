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

public class ClientMain {

    private final String host;
    private final int port;
    private final int preferredUdpPort;
    private int actualUdpPort;
    private final Gson prettyGson;
    private DatagramSocket udpSocket;

    public ClientMain(String host, int port, int preferredUdpPort) {
        this.host = host;
        this.port = port;
        this.preferredUdpPort = preferredUdpPort;
        this.prettyGson = new GsonBuilder().setPrettyPrinting().create();
    }

    public void start() {
        startUdpNotificationListener();

        try (SocketChannel socketChannel = SocketChannel.open()) {
            socketChannel.configureBlocking(true);
            socketChannel.connect(new InetSocketAddress(host, port));

            System.out.println("[CLIENT] Connesso al server TCP " + host + ":" + port);
            System.out.println("[CLIENT] In ascolto per notifiche UDP sulla porta " + actualUdpPort);
            System.out.println("[CLIENT] Incolla o scrivi il JSON di richiesta (oppure 'exit' per uscire):");

            @SuppressWarnings("resource")
            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print("\n> ");
                if (!scanner.hasNextLine()) break;

                String line = scanner.nextLine().trim();

                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    System.out.println("[CLIENT] Disconnessione in corso...");
                    break;
                }

                if (line.isEmpty()) continue;

                try {
                    JsonObject jsonReq = JsonParser.parseString(line).getAsJsonObject();

                    if (jsonReq.has("operation") && jsonReq.get("operation").getAsString().equals("login")) {
                        jsonReq.addProperty("udpPort", actualUdpPort);
                    }

                    boolean continueLoop = sendAndReceive(socketChannel, jsonReq.toString());

                    if (jsonReq.has("operation") && jsonReq.get("operation").getAsString().equals("logout")) {
                        System.out.println("[CLIENT] Logout completato. Chiusura del client.");
                        break;
                    }

                    if (!continueLoop) break;

                } catch (Exception e) {
                    System.err.println("[ERRORE CLIENT] Input non valido: inserire una stringa JSON corretta.");
                }
            }

        } catch (IOException e) {
            System.err.println("[CLIENT] Errore di connessione: " + e.getMessage());
        } finally {
            closeUdpSocket();
            System.out.println("[CLIENT] Client terminato in modo pulito.");
        }
    }

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
                byte[] buffer = new byte[2048];
                while (!udpSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);
                    String notification = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                    System.out.println("\n\n[NOTIFICA ASINCRONA UDP]: " + notification);
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

    private void closeUdpSocket() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }

    private boolean sendAndReceive(SocketChannel socketChannel, String jsonPayload) throws IOException {
        String msg = jsonPayload + "\n";
        socketChannel.write(ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8)));

        StringBuilder responseBuilder = new StringBuilder();
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);

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