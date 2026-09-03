import java.util.Map;

/**
 * Rappresenta la risposta standardizzata inviata dal server al client.
 * Strutturata per la serializzazione JSON tramite la libreria GSON.
 */
public class Response {
    private String status;
    private String message;
    private Map<String, Object> data;

    // Costruttore vuoto richiesto da GSON per la reflection
    public Response() {}

    /**
     * Costruisce una risposta con stato e messaggio specificati.
     * 
     */
    public Response(String status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * Costruisce una risposta di successo con messaggio specificato.
     */
    public static Response ok(String message) {
        return new Response("OK", message);
    }

    /**
     * Costruisce una risposta di errore con messaggio specificato.
     */
    public static Response error(String message) {
        return new Response("ERROR", message);
    }

    /**
     * Costruisce una risposta di errore di autenticazione con messaggio specificato.
     */
    public static Response unauthorized(String message) {
        return new Response("UNAUTHORIZED", message);
    }

    // Getters e setters
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}