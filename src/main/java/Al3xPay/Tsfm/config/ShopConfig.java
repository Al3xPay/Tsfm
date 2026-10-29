package Al3xPay.Tsfm.config;

import Al3xPay.Tsfm.Tsfm;
import Al3xPay.Tsfm.data.PromoCode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopConfig {

    private static final Pattern DURATION = Pattern.compile("^(\\d+)d\\s+(\\d+)h\\s+(\\d+)m$");

    private final Tsfm plugin;

    private String token = "";
    private String botUsername = "YourShopBot";
    private final List<Admin> admins = new ArrayList<>();
    private String supportUrl = "";
    private String supportText = "";
    private boolean agreementsEnabled = true;
    private String privacyUrl = "";
    private String privacyText = "Прочтите Политику конфиденциальности";
    private String termsUrl = "";
    private String termsText = "Прочтите Пользовательское соглашение";
    private boolean paymentEnabled = false;
    private boolean paymentSandbox = false;
    private String terminalId = "";
    private String apiKey = "";
    private String webhookSecret = "";
    private String apiUrl = "https://api.example.com/v1/payments";
    private String callbackUrl = "";
    private String successUrl = "";
    private String failUrl = "";
    private String paymentSupportUrl = "";
    private int webhookPort = 8080;
    private final LinkedHashMap<String, Product> products = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> messages = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> buttons = new LinkedHashMap<>();

    public ShopConfig(Tsfm plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        token = c.getString("bot.token", "");
        botUsername = c.getString("bot.username", "YourShopBot");

        admins.clear();
        for (Map<?, ?> m : c.getMapList("admins")) {
            Admin a = new Admin();
            Object u = m.get("username");
            Object i = m.get("id");
            a.username = u == null ? "" : u.toString();
            a.id = i instanceof Number ? ((Number) i).longValue() : -1;
            admins.add(a);
        }

        supportUrl = c.getString("support.url", "");
        supportText = c.getString("support.text", "По любым вопросам покупки пишите в чат поддержки!");

        agreementsEnabled = c.getBoolean("agreements.enabled", true);
        privacyUrl = c.getString("agreements.privacy.url", "");
        privacyText = c.getString("agreements.privacy.text", "Прочтите Политику конфиденциальности");
        termsUrl = c.getString("agreements.terms.url", "");
        termsText = c.getString("agreements.terms.text", "Прочтите Пользовательское соглашение");

        paymentEnabled = c.getBoolean("payment.enabled", false);
        paymentSandbox = c.getBoolean("payment.sandbox", false);
        terminalId = c.getString("payment.provider.terminal-id", "");
        apiKey = c.getString("payment.provider.api-key", "");
        webhookSecret = c.getString("payment.provider.webhook-secret", "");
        apiUrl = c.getString("payment.provider.api-url", "https://api.example.com/v1/payments");
        callbackUrl = c.getString("payment.callback-url", "");
        successUrl = c.getString("payment.success-url", "");
        failUrl = c.getString("payment.fail-url", "");
        paymentSupportUrl = c.getString("payment.support-url", "");
        webhookPort = c.getInt("payment.webhook-port", 8080);

        products.clear();
        ConfigurationSection ps = c.getConfigurationSection("products");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(key);
                if (s == null) continue;
                Product p = new Product();
                p.name = s.getString("name", key);
                p.price = s.getInt("price", 0);
                p.command = s.getString("command", "");
                products.put(key, p);
            }
        }

        messages.clear();
        ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                messages.put(key, ms.getString(key, ""));
            }
        }

        buttons.clear();
        ConfigurationSection bs = c.getConfigurationSection("buttons");
        if (bs != null) {
            for (String key : bs.getKeys(false)) {
                buttons.put(key, bs.getString(key, ""));
            }
        }
    }

    public String getToken() {
        return token;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public List<Admin> admins() {
        return admins;
    }

    public boolean isAdmin(long id, String username) {
        for (Admin a : admins) {
            if (a.id > 0 && a.id == id) return true;
            if (username != null && !username.isEmpty() && !a.username.isEmpty()) {
                if (strip(a.username).equalsIgnoreCase(strip(username))) return true;
            }
        }
        return false;
    }

    private static String strip(String s) {
        s = s.trim();
        if (s.startsWith("@")) s = s.substring(1);
        return s;
    }

    public String supportUrl() {
        return supportUrl;
    }

    public String supportText() {
        return supportText;
    }

    public boolean agreementsEnabled() {
        return agreementsEnabled;
    }

    public String privacyUrl() {
        return privacyUrl;
    }

    public String privacyText() {
        return privacyText;
    }

    public String termsUrl() {
        return termsUrl;
    }

    public String termsText() {
        return termsText;
    }

    public boolean paymentEnabled() {
        return paymentEnabled;
    }

    public boolean paymentSandbox() {
        return paymentSandbox;
    }

    public String terminalId() {
        return terminalId;
    }

    public String apiKey() {
        return apiKey;
    }

    public String webhookSecret() {
        return webhookSecret;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    public String successUrl() {
        return successUrl;
    }

    public String failUrl() {
        return failUrl;
    }

    public String paymentSupportUrl() {
        return paymentSupportUrl;
    }

    public int webhookPort() {
        return webhookPort;
    }

    public Product product(String key) {
        return products.get(key);
    }

    public List<String> productKeys() {
        return new ArrayList<>(products.keySet());
    }

    public String msg(String key, Object... args) {
        String m = messages.get(key);
        if (m == null) m = "";
        for (int i = 0; i + 1 < args.length; i += 2) {
            m = m.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return m;
    }

    public String btn(String key) {
        String b = buttons.get(key);
        return b == null ? "" : b;
    }

    public List<PromoCode> initialPromos() {
        List<Map<?, ?>> list = plugin.getConfig().getMapList("promocodes");
        List<PromoCode> result = new ArrayList<>();
        for (Map<?, ?> m : list) {
            Object name = m.get("name");
            Object percent = m.get("percent");
            Object activations = m.get("activations");
            Object duration = m.get("duration");
            if (name == null || percent == null || duration == null) continue;
            try {
                int pct = Integer.parseInt(String.valueOf(percent));
                long act = activations == null ? -1 : (String.valueOf(activations).equals("-") ? -1 : Long.parseLong(String.valueOf(activations)));
                Matcher mm = DURATION.matcher(String.valueOf(duration));
                if (!mm.matches()) continue;
                long dur = Long.parseLong(mm.group(1)) * 86400000L
                        + Long.parseLong(mm.group(2)) * 3600000L
                        + Long.parseLong(mm.group(3)) * 60000L;
                if (dur <= 0) dur = 86400000L;
                result.add(new PromoCode(String.valueOf(name), pct, act, System.currentTimeMillis() + dur));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    public static class Admin {
        public String username = "";
        public long id = -1;
    }

    public static class Product {
        public String name;
        public int price;
        public String command;
    }
}
