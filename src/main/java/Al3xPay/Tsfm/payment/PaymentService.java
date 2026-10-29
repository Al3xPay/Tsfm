package Al3xPay.Tsfm.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public class PaymentService {

    private final String terminalId;
    private final String apiKey;
    private final String webhookSecret;
    private final String apiUrl;
    private final String callbackUrl;
    private final String successUrl;
    private final String failUrl;
    private final String supportUrl;
    private final boolean sandbox;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;

    public static class PaymentResult {
        public final String payUrl;
        public final String paymentId;
        public PaymentResult(String payUrl, String paymentId) {
            this.payUrl = payUrl;
            this.paymentId = paymentId;
        }
    }

    public PaymentService(Logger logger, String terminalId, String apiKey, String webhookSecret,
                          String apiUrl, String callbackUrl, String successUrl,
                          String failUrl, String supportUrl, boolean sandbox) {
        this.logger = logger;
        this.terminalId = terminalId;
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret;
        this.apiUrl = apiUrl;
        this.callbackUrl = callbackUrl;
        this.successUrl = successUrl;
        this.failUrl = failUrl;
        this.supportUrl = supportUrl;
        this.sandbox = sandbox;
    }

     // Создаёт платёж через API платёжного провайдера.

    public PaymentResult createPayment(String orderId, int amount) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("terminal_id", terminalId);
            body.put("amount", String.format(Locale.US, "%.2f", (double) amount));
            body.put("payment_currency", "RUB");
            body.put("order_id", orderId);
            if (sandbox) body.put("test", true);
            if (callbackUrl != null && !callbackUrl.isEmpty()) body.put("callback_url", callbackUrl);
            if (successUrl != null && !successUrl.isEmpty()) body.put("success_url", successUrl);
            if (failUrl != null && !failUrl.isEmpty()) body.put("fail_url", failUrl);
            if (supportUrl != null && !supportUrl.isEmpty()) body.put("support_url", supportUrl);

            String json = mapper.writeValueAsString(body);
            logger.info("[Payment] Создание платежа #" + orderId + (sandbox ? " [SANDBOX]" : " [PROD]"));

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-API-Key", apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            if (sandbox) conn.setRequestProperty("X-Test-Mode", "true");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = is != null ? readAll(is) : "";

            if (code < 200 || code >= 300) {
                logger.warning("[Payment] Ошибка HTTP " + code + ": " + responseBody);
                return null;
            }

            JsonNode root = mapper.readTree(responseBody);
            String payUrl = root.has("pay_url") ? root.get("pay_url").asText() : null;
            String paymentId = root.has("payment_id") ? root.get("payment_id").asText() : null;
            String environment = root.has("environment") ? root.get("environment").asText() : "unknown";

            logger.info("[Payment] Платёж создан: payment_id=" + paymentId + " environment=" + environment);

            if (payUrl == null) {
                logger.warning("[Payment] Нет pay_url в ответе");
                return null;
            }
            return new PaymentResult(payUrl, paymentId);
        } catch (Exception e) {
            logger.severe("[Payment] Ошибка создания платежа: " + e.getMessage());
            return null;
        }
    }

     // Проверяет статус платежа по payment_id через GET-запрос к API.
     
    public String checkPaymentStatus(String paymentId) {
        try {
            String urlStr = apiUrl + "/" + paymentId;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-API-Key", apiKey);
            if (sandbox) conn.setRequestProperty("X-Test-Mode", "true");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = is != null ? readAll(is) : "";

            if (code < 200 || code >= 300) {
                logger.warning("[Payment] GET статус " + paymentId + ": HTTP " + code);
                return null;
            }

            JsonNode root = mapper.readTree(body);
            if (root.has("status")) {
                String status = root.get("status").asText();
                logger.info("[Payment] Статус " + paymentId + ": " + status);
                return status;
            }
            return null;
        } catch (Exception e) {
            logger.warning("[Payment] Ошибка проверки статуса " + paymentId + ": " + e.getMessage());
            return null;
        }
    }

     //Проверяет подпись вебхука.

    public boolean verifySignature(String body, String signature) {
        if (webhookSecret == null || webhookSecret.isEmpty()) return true;
        if (signature == null || signature.isEmpty()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String hex = bytesToHex(hash);
            String b64 = java.util.Base64.getEncoder().encodeToString(hash);
            String b64url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return hex.equalsIgnoreCase(signature)
                    || b64.equals(signature)
                    || b64url.equals(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.warning("[Payment] Ошибка проверки подписи: " + e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
