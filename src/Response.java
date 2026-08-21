import java.util.Map;

public class Response {
    private String status;
    private String message;
    private Map<String, Object> data;

    public Response() {}

    public Response(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public static Response ok(String message) {
        return new Response("OK", message);
    }

    public static Response error(String message) {
        return new Response("ERROR", message);
    }

    public static Response unauthorized(String message) {
        return new Response("UNAUTHORIZED", message);
    }

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