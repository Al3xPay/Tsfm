package Al3xPay.Tsfm.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class WebhookServer {

    private final PaymentService paymentService;
    private final WebhookHandler handler;
    private final Logger logger;
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public interface WebhookHandler {
        void onPaymentStatus(String orderId, String status);
    }

    public WebhookServer(PaymentService paymentService, WebhookHandler handler, Logger logger) {
        this.paymentService = paymentService;
        this.handler = handler;
        this.logger = logger;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/payments/callback", this::handleCallback);
        server.setExecutor(null);
        server.start();
        logger.info("[Payment] Webhook-сервер запущен на порту " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("[Payment] Webhook-сервер остановлен");
        }
    }

    private void handleCallback(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendResponse(ex, 405, "Method Not Allowed");
            return;
        }

        String signature = ex.getRequestHeaders().getFirst("X-Signature");
        String body = readAll(ex.getRequestBody());

        logger.info("[Payment] Получен вебхук: " + body);

        if (!paymentService.verifySignature(body, signature)) {
            logger.warning("[Payment] Неверная подпись вебхука!");
            sendResponse(ex, 403, "Invalid Signature");
            return;
        }

        try {
            JsonNode root = mapper.readTree(body);
            String orderId = root.has("order_id") ? root.get("order_id").asText() : null;
            String status = root.has("status") ? root.get("status").asText() : null;

            if (orderId != null && status != null) {
                logger.info("[Payment] Вебхук: заказ #" + orderId + " статус = " + status);
                handler.onPaymentStatus(orderId, status);
            } else {
                logger.warning("[Payment] Вебхук без order_id или status: " + body);
            }
        } catch (Exception e) {
            logger.warning("[Payment] Ошибка парсинга вебхука: " + e.getMessage());
        }

        sendResponse(ex, 200, "OK");
    }

    private void sendResponse(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toString("UTF-8");
    }
}
