// M0 WS 冒烟：auth → ready；ping → pong；msg → ack+echo。用法: java WsSmoke <token>
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class WsSmoke {
    static CompletableFuture<String> done = new CompletableFuture<>();
    static StringBuilder received = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String token = args[0];
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                received.append(data).append('\n');
                if (data.toString().contains("echo")) done.complete("OK");
                ws.request(1);
                return null;
            }
            @Override public void onError(WebSocket ws, Throwable error) { done.completeExceptionally(error); }
        };
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:8080/ws"), listener)
                .get(5, TimeUnit.SECONDS);

        ws.sendText("{\"type\":\"auth\",\"payload\":{\"token\":\"" + token + "\"}}", true);
        Thread.sleep(300);
        ws.sendText("{\"type\":\"ping\"}", true);
        Thread.sleep(300);
        ws.sendText("{\"type\":\"msg\",\"payload\":{\"clientMsgId\":\"c1\",\"body\":\"hello teamone\"}}", true);

        String result = done.get(5, TimeUnit.SECONDS);
        System.out.println("---- frames received ----");
        System.out.println(received);
        System.out.println("WS_SMOKE=" + result);
        ws.abort();
    }
}
