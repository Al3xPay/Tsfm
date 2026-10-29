package Al3xPay.Tsfm.bot;

import Al3xPay.Tsfm.Tsfm;
import Al3xPay.Tsfm.config.ShopConfig;
import Al3xPay.Tsfm.data.BannedUser;
import Al3xPay.Tsfm.data.DataManager;
import Al3xPay.Tsfm.data.Order;
import Al3xPay.Tsfm.data.OrderItem;
import Al3xPay.Tsfm.data.PlayerData;
import Al3xPay.Tsfm.data.PromoCode;
import Al3xPay.Tsfm.data.PurchaseRecord;
import Al3xPay.Tsfm.payment.PaymentService;
import org.bukkit.Bukkit;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Flow {

    private static final Pattern NICK_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern PROMO_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{2,20}$");
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)d\\s+(\\d+)h\\s+(\\d+)m$");
    private static final int ORDERS_PER_PAGE = 5;

    private final Tsfm plugin;
    private final ShopBot bot;
    private final ShopConfig cfg;
    private final DataManager data;
    private PaymentService paymentService;

    private final Map<Long, String> states = new ConcurrentHashMap<>();
    private final Map<Long, String> regionDrafts = new ConcurrentHashMap<>();
    private final Map<Long, String> broadcastDrafts = new ConcurrentHashMap<>();
    private final Map<Long, String> replyOrders = new ConcurrentHashMap<>();
    private final Map<Long, PromoDraft> promoDrafts = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Integer>> carts = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> acceptedDocs = new ConcurrentHashMap<>();

    public Flow(Tsfm plugin, ShopBot bot) {
        this.plugin = plugin;
        this.bot = bot;
        this.cfg = plugin.getShopConfig();
        this.data = plugin.getData();
    }

    public void setPaymentService(PaymentService paymentService) {
        this.paymentService = paymentService;
        if (paymentService != null) startPaymentPolling();
    }

    private void startPaymentPolling() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (paymentService == null) return;
            for (Order o : new ArrayList<>(data.orders())) {
                if (!o.state.equals(Order.PAYING)) continue;
                if (o.paymentId == null || o.paymentId.isEmpty()) continue;
                if (System.currentTimeMillis() - o.createdAt > 1800000L) {
                    o.state = Order.CANCELLED;
                    data.orders().remove(o);
                    data.save();
                    PlayerData pd = data.getPlayer(o.playerId);
                    if (pd != null) bot.send(o.playerId, cfg.msg("pay-failed", "order", o.id));
                    continue;
                }
                String status = paymentService.checkPaymentStatus(o.paymentId);
                if (status != null && (status.equalsIgnoreCase("paid")
                        || status.equalsIgnoreCase("succeeded")
                        || status.equalsIgnoreCase("failed")
                        || status.equalsIgnoreCase("cancelled"))) {
                    onPaymentStatus(o.id, status);
                }
            }
        }, 200L, 200L);
    }
    //  Входящие сообщения
    public void onMessage(Message m) {
        long chat = m.getChatId();
        String username = m.getFrom().getUserName();
        String text = m.getText();

        if (isBanned(username)) {
            bot.send(chat, cfg.msg("banned"));
            return;
        }

        if (text.startsWith("/start")) {
            states.remove(chat);
            onStart(chat, username);
            return;
        }

        String state = states.get(chat);
        if (state != null) {
            switch (state) {
                case "NICK":
                    handleNick(m);
                    break;
                case "PROMO":
                    handlePromoInput(m);
                    break;
                case "PROMO_NAME":
                    handlePromoName(m);
                    break;
                case "PROMO_PERCENT":
                    handlePromoPercent(m);
                    break;
                case "PROMO_ACTIVATIONS":
                    handlePromoActivations(m);
                    break;
                case "PROMO_DURATION":
                    handlePromoDuration(m);
                    break;
                case "BAN":
                    handleBanInput(m);
                    break;
                case "UNBAN":
                    handleUnbanInput(m);
                    break;
                case "BROADCAST":
                    handleBroadcastInput(m);
                    break;
                case "REPLY":
                    handleAdminReply(m);
                    break;
            }
            return;
        }

        PlayerData p = data.getPlayer(chat);
        if (isAdmin(chat, username)) {
            showAdminMenu(chat, 0);
            return;
        }
        if (p != null) {
            if (username != null && !username.isEmpty()) p.username = username;
            showProfile(chat, 0, p);
            return;
        }
        onStart(chat, username);
    }

    private void onStart(long chat, String username) {
        if (isBanned(username)) {
            bot.send(chat, cfg.msg("banned"));
            return;
        }
        PlayerData p = data.getPlayer(chat);
        if (p != null) showProfile(chat, 0, p);
        else showAgreement(chat, 0, "privacy");
    }

    //  Кнопки

    public void onCallback(CallbackQuery q) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        String callbackId = q.getId();
        String d = q.getData() == null ? "" : q.getData();
        String username = q.getFrom().getUserName();

        states.remove(chat);

        if (isBanned(username)) {
            bot.answer(callbackId, cfg.msg("banned"));
            bot.send(chat, cfg.msg("banned"));
            return;
        }

        String[] parts = d.split("\\|", 3);

        if (parts[0].startsWith("a")) {
            if (!isAdmin(chat, username)) {
                bot.answer(callbackId, "⛔ Это действие только для администраторов");
                return;
            }
        }

        try {
            switch (parts[0]) {
                case "r": handleRegion(q, parts); break;
                case "prof": showProfile(chat, mid, data.getPlayer(chat)); break;
                case "sh": openShop(chat, mid, data.getPlayer(chat)); break;
                case "buy": buyItem(q, parts[1]); break;
                case "cart": openCart(chat, mid, data.getPlayer(chat)); break;
                case "crm": removeFromCart(q, parts[1]); break;
                case "cinfo": bot.answer(callbackId, cfg.msg("cart-item-hint")); break;
                case "pinfo": bot.answer(callbackId, cfg.msg("promo-item-hint")); break;
                case "pay": pay(q); break;
                case "promo": enterPromo(chat, mid); break;
                case "hist": showHistory(chat, mid, data.getPlayer(chat)); break;
                case "cr": showRegionSelect(chat, mid, true); break;
                case "sup": showSupport(chat, mid); break;
                case "am": showAdminMenu(chat, mid); break;
                case "ast": analyticsMenu(chat, mid); break;
                case "asp": analyticsResult(chat, mid, parts[1]); break;
                case "acp": promoCreateStart(chat, mid); break;
                case "apl": promoList(chat, mid); break;
                case "apd": promoDeleteConfirm(chat, mid, parts[1]); break;
                case "apdy": promoDelete(chat, mid, parts[1]); break;
                case "apdn": promoList(chat, mid); break;
                case "abr": broadcastStart(chat, mid); break;
                case "abry": broadcastSend(chat, mid); break;
                case "abrn": broadcastCancel(chat, mid); break;
                case "aban": banStart(chat, mid); break;
                case "abal": bannedList(chat, mid); break;
                case "atake": takeOrder(q, parts[1]); break;
                case "anext": nextOrder(q, parts[1]); break;
                case "areply": adminReplyStart(q, parts[1]); break;
                case "aconf": confirmPayment(q, parts[1]); break;
                case "acncl": cancelPayment(q, parts[1]); break;
                case "aord": showOrder(q, parts[1], parts.length > 2 ? parts[2] : "1"); break;
                case "aols": showActiveOrders(chat, mid, parseInt(parts[1], 1)); break;
                case "doc": handleAgreement(q, parts); break;
                default:
                    bot.answer(callbackId, "Неизвестная кнопка");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка обработки кнопки [" + d + "]: " + e.getMessage());
        }
    }

    //  Утилиты

    private boolean isAdmin(long id, String username) {
        return cfg.isAdmin(id, username);
    }

    private boolean isBanned(String username) {
        return username != null && !username.isEmpty() && data.isBanned(username);
    }

    private InlineKeyboardButton btn(String label, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(label);
        b.setCallbackData(data);
        return b;
    }

    private InlineKeyboardButton urlBtn(String label, String url) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(label);
        b.setUrl(url);
        return b;
    }

    private List<InlineKeyboardButton> row1(InlineKeyboardButton b) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (b != null) row.add(b);
        return row;
    }

    private List<InlineKeyboardButton> row2(InlineKeyboardButton a, InlineKeyboardButton b) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(a);
        if (b != null) row.add(b);
        return row;
    }

    private List<List<InlineKeyboardButton>> rows1(InlineKeyboardButton b) {
        return rows2(b, null);
    }

    private List<List<InlineKeyboardButton>> rows2(InlineKeyboardButton a, InlineKeyboardButton b) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(a);
        if (b != null) row.add(b);
        rows.add(row);
        return rows;
    }

    private static List<List<InlineKeyboardButton>> combine(List<List<InlineKeyboardButton>> a, List<List<InlineKeyboardButton>> b) {
        List<List<InlineKeyboardButton>> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    private static final List<List<InlineKeyboardButton>> NO_BUTTONS = new ArrayList<>();

    private String date(long ms) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(ms));
    }

    private String dateShort(long ms) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(ms));
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String usernameText(String username) {
        return username == null || username.isEmpty() ? "Администратор" : "@" + username;
    }

    private String adminNameOf(Long adminId) {
        for (ShopConfig.Admin a : cfg.admins()) {
            if (a.id == adminId) {
                String u = a.username;
                if (u == null || u.isEmpty()) return "Администратор";
                return u.startsWith("@") ? u : "@" + u;
            }
        }
        return "Администратор";
    }

    private Order findOrder(String id) {
        return data.findOrder(id);
    }

    private PromoCode findPromo(String name) {
        return data.findPromo(name);
    }

    private List<Order> activeOrders() {
        List<Order> list = new ArrayList<>();
        for (Order o : data.orders()) {
            if (o.isActive()) list.add(o);
        }
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    private Order activeOrder(PlayerData p) {
        for (Order o : data.orders()) {
            if (o.playerId == p.id && o.isActive()) return o;
        }
        return null;
    }

    private PromoCode validPromo(PlayerData p) {
        if (p.pendingPromo == null || p.pendingPromo.isEmpty()) return null;
        PromoCode pr = findPromo(p.pendingPromo);
        if (pr == null || pr.isExpired() || !pr.hasActivations() || pr.usedByPlayer(p.id)) return null;
        return pr;
    }

    private String cartSummary(Map<String, Integer> cart) {
        if (cart == null || cart.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            ShopConfig.Product pr = cfg.product(e.getKey());
            if (pr == null) continue;
            parts.add(pr.name + " x" + e.getValue());
        }
        return String.join(", ", parts);
    }

    private String itemsText(Order o) {
        List<String> parts = new ArrayList<>();
        for (OrderItem it : o.items) parts.add(it.name + " x" + it.quantity);
        return String.join(", ", parts);
    }

    private String orderInfoText(Order o) {
        List<String> lines = new ArrayList<>();
        for (OrderItem it : o.items) {
            lines.add(cfg.msg("cart-item-line", "name", it.name, "quantity", it.quantity, "sum", it.price * it.quantity));
        }
        String promoLine = "";
        if (o.promo != null && !o.promo.isEmpty()) {
            promoLine = cfg.msg("cart-discount", "promo", o.promo, "percent", o.promoPercent, "saved", o.baseTotal() - o.total);
        }
        return cfg.msg("admin-order-notify", "order", o.id, "nick", o.nickname, "region", o.region,
                "items", String.join("\n", lines), "total", o.total, "promo_line", promoLine);
    }

    private String statusText(Order o, long viewer) {
        if (o.state.equals(Order.TAKEN)) {
            if (o.takenBy != null && o.takenBy == viewer) {
                return "\n\n" + cfg.msg("order-status-taken-by-me", "order", o.id);
            }
            return "\n\n" + cfg.msg("admin-order-taken-by-other", "order", o.id, "admin", adminNameOf(o.takenBy));
        }
        return "";
    }

    private List<List<InlineKeyboardButton>> controls(Order o, long viewer) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (o.state.equals(Order.PENDING)) {
            rows.add(row2(btn(cfg.btn("take-order"), "atake|" + o.id), btn(cfg.btn("next-order"), "anext|" + o.id)));
        } else if (o.state.equals(Order.TAKEN)) {
            if (o.takenBy != null && o.takenBy == viewer) {
                rows.add(row2(btn(cfg.btn("write-player"), "areply|" + o.id), btn(cfg.btn("confirm-payment"), "aconf|" + o.id)));
                rows.add(row1(btn(cfg.btn("cancel-payment"), "acncl|" + o.id)));
            } else {
                rows.add(row1(btn(cfg.btn("next-order"), "anext|" + o.id)));
            }
        }
        return rows;
    }

    private String genOrderId() {
        Random r = new Random();
        String id;
        do {
            id = String.valueOf(100000 + r.nextInt(900000));
        } while (data.findOrder(id) != null);
        return id;
    }

    //  Соглашения (Политика конфиденциальности / Пользовательское соглашение)

    private void showAgreement(long chat, int mid, String stage) {
        if (!cfg.agreementsEnabled()) {
            PlayerData p = data.getPlayer(chat);
            if (p == null) showRegionSelect(chat, mid, false);
            else showProfile(chat, mid, p);
            return;
        }
        boolean privacy = stage.equals("privacy");
        String text = cfg.msg(privacy ? "agreement-privacy" : "agreement-terms",
                "text", privacy ? cfg.privacyText() : cfg.termsText());
        String url = privacy ? cfg.privacyUrl() : cfg.termsUrl();
        String tag = privacy ? "priv" : "terms";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (url != null && !url.isEmpty()) {
            rows.add(row1(urlBtn(cfg.btn(privacy ? "agree-privacy" : "agree-terms"), url)));
        }
        rows.add(row2(btn(cfg.btn("agree-read"), "doc|" + tag + "|ok"),
                btn(cfg.btn("agree-next"), "doc|" + tag + "|next")));
        if (mid == 0) bot.send(chat, text, rows);
        else bot.edit(chat, mid, text, rows);
    }

    private void handleAgreement(CallbackQuery q, String[] parts) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        String doc = parts.length > 1 ? parts[1] : "";
        String action = parts.length > 2 ? parts[2] : "";
        if (doc.equals("priv")) {
            if (action.equals("ok")) acceptDoc(chat, "privacy");
            showAgreement(chat, mid, "terms");
            return;
        }
        if (doc.equals("terms")) {
            if (action.equals("ok")) acceptDoc(chat, "terms");
            PlayerData p = data.getPlayer(chat);
            if (p == null) showRegionSelect(chat, mid, false);
            else showProfile(chat, mid, p);
        }
    }

    private void acceptDoc(long chat, String doc) {
        PlayerData p = data.getPlayer(chat);
        if (p != null) {
            if (doc.equals("privacy")) p.privacyAccepted = true;
            else p.termsAccepted = true;
            data.save();
            return;
        }
        acceptedDocs.computeIfAbsent(chat, k -> new HashSet<>()).add(doc);
    }

    private boolean docsAccepted(long chat, PlayerData p) {
        if (!cfg.agreementsEnabled()) return true;
        if (p != null) return p.privacyAccepted && p.termsAccepted;
        Set<String> set = acceptedDocs.get(chat);
        return set != null && set.contains("privacy") && set.contains("terms");
    }

    private String unacceptedDoc(long chat, PlayerData p) {
        if (p != null) {
            if (!p.privacyAccepted) return "privacy";
            return "terms";
        }
        Set<String> set = acceptedDocs.get(chat);
        if (set == null || !set.contains("privacy")) return "privacy";
        return "terms";
    }

    //  Регистрация и регионы

    private void showRegionSelect(long chat, int mid, boolean change) {
        String russia = cfg.btn("region-russia");
        String other = cfg.btn("region-other");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row2(
                btn(russia, "r|" + russia + "|" + (change ? 1 : 0)),
                btn(other, "r|" + other + "|" + (change ? 1 : 0))));
        if (change) rows.add(row1(btn(cfg.btn("back"), "prof")));
        if (mid == 0) bot.send(chat, cfg.msg("choose-region"), rows);
        else bot.edit(chat, mid, cfg.msg("choose-region"), rows);
    }

    private void handleRegion(CallbackQuery q, String[] parts) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        String region = parts[1];
        boolean change = parts.length > 2 && parts[2].equals("1");
        PlayerData p = data.getPlayer(chat);
        if (p != null) {
            p.country = region;
            data.save();
            showProfile(chat, mid, p);
            return;
        }
        regionDrafts.put(chat, region);
        states.put(chat, "NICK");
        bot.edit(chat, mid, cfg.msg("enter-nick"), NO_BUTTONS);
    }

    private void handleNick(Message m) {
        long chat = m.getChatId();
        String region = regionDrafts.get(chat);
        String nick = m.getText().trim();
        if (!NICK_PATTERN.matcher(nick).matches()) {
            bot.send(chat, cfg.msg("nick-invalid"));
            return;
        }
        if (data.getPlayerByNick(nick) != null) {
            bot.send(chat, cfg.msg("nick-taken"));
            return;
        }
        states.remove(chat);
        regionDrafts.remove(chat);
        String username = m.getFrom().getUserName();
        PlayerData p = new PlayerData(chat, username);
        p.nickname = nick;
        p.country = region == null ? "" : region;
        Set<String> accepted = acceptedDocs.remove(chat);
        if (accepted != null) {
            p.privacyAccepted = accepted.contains("privacy");
            p.termsAccepted = accepted.contains("terms");
        }
        data.register(p);
        data.save();
        bot.send(chat, cfg.msg("profile",
                "nick", p.nickname,
                "country", p.country,
                "purchases", p.purchaseCount,
                "promos", p.promoCount,
                "date", date(p.registeredAt)), profileButtons(p));
    }

    //  Профиль и меню игрока

    private List<List<InlineKeyboardButton>> profileButtons(PlayerData p) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row2(btn(cfg.btn("shop"), "sh"), btn(cfg.btn("promo"), "promo")));
        rows.add(row2(btn(cfg.btn("history"), "hist"), btn(cfg.btn("change-region"), "cr")));
        String privacyUrl = cfg.privacyUrl();
        String termsUrl = cfg.termsUrl();
        if ((privacyUrl != null && !privacyUrl.isEmpty()) || (termsUrl != null && !termsUrl.isEmpty())) {
            rows.add(row2(
                    (privacyUrl != null && !privacyUrl.isEmpty()) ? urlBtn(cfg.btn("agree-privacy"), privacyUrl) : null,
                    (termsUrl != null && !termsUrl.isEmpty()) ? urlBtn(cfg.btn("agree-terms"), termsUrl) : null));
        }
        rows.add(row1(btn(cfg.btn("support"), "sup")));
        if (cfg.isAdmin(p.id, p.username)) {
            rows.add(row1(btn(cfg.btn("admin-menu"), "am")));
        }
        return rows;
    }

    private void showProfile(long chat, int mid, PlayerData p) {
        if (p == null) {
            showAgreement(chat, mid, "privacy");
            return;
        }
        String text = cfg.msg("profile",
                "nick", p.nickname,
                "country", p.country,
                "purchases", p.purchaseCount,
                "promos", p.promoCount,
                "date", date(p.registeredAt));
        if (mid == 0) bot.send(chat, text, profileButtons(p));
        else bot.edit(chat, mid, text, profileButtons(p));
    }

    private void showSupport(long chat, int mid) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (cfg.supportUrl() != null && !cfg.supportUrl().isEmpty()) {
            rows.add(row1(urlBtn(cfg.btn("support"), cfg.supportUrl())));
        }
        rows.add(row1(btn(cfg.btn("back"), "prof")));
        if (mid == 0) bot.send(chat, cfg.msg("support", "text", cfg.supportText()), rows);
        else bot.edit(chat, mid, cfg.msg("support", "text", cfg.supportText()), rows);
    }

    private void showHistory(long chat, int mid, PlayerData p) {
        if (p == null) {
            showRegionSelect(chat, mid, false);
            return;
        }
        List<Object[]> entries = new ArrayList<>();
        for (Order o : data.orders()) {
            if (o.playerId == p.id) {
                String status = orderStatusText(o.state);
                String line = cfg.msg("history-line-status", "items", itemsText(o), "total", o.total,
                        "date", dateShort(o.createdAt), "status", status);
                entries.add(new Object[]{o.createdAt, line});
            }
        }
        for (PurchaseRecord pr : p.purchases) {
            String line = cfg.msg("history-line-status", "items", pr.items, "total", pr.total,
                    "date", dateShort(pr.date), "status", "✅ Оплачен");
            entries.add(new Object[]{pr.date, line});
        }
        entries.sort((a, b) -> Long.compare((long) b[0], (long) a[0]));
        List<String> lines = new ArrayList<>();
        for (Object[] e : entries) lines.add((String) e[1]);
        String text = lines.isEmpty()
                ? cfg.msg("history-empty")
                : cfg.msg("history", "history", String.join("\n\n", lines));
        if (mid == 0) bot.send(chat, text, rows1(btn(cfg.btn("back"), "prof")));
        else bot.edit(chat, mid, text, rows1(btn(cfg.btn("back"), "prof")));
    }

    private String orderStatusText(String state) {
        switch (state) {
            case Order.PENDING: return "⏳ Ожидает оплаты";
            case Order.TAKEN: return "🔄 В обработке";
            case Order.CONFIRMED: return "✅ Оплачен";
            case Order.CANCELLED: return "❌ Отменён";
            default: return state;
        }
    }

    //  Магазин и корзина

    private void openShop(long chat, int mid, PlayerData p) {
        if (p == null) {
            showRegionSelect(chat, mid, false);
            return;
        }
        Order active = activeOrder(p);
        if (active != null) {
            String t = cfg.msg("active-order", "order", active.id);
            if (mid == 0) bot.send(chat, t);
            else bot.edit(chat, mid, t);
            return;
        }
        List<String> lines = new ArrayList<>();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (String key : cfg.productKeys()) {
            ShopConfig.Product pr = cfg.product(key);
            if (pr == null) continue;
            lines.add(cfg.msg("shop-product-line", "name", pr.name, "price", pr.price));
            row.add(btn(cfg.msg("shop-product-line", "name", pr.name, "price", pr.price), "buy|" + key));
            if (row.size() == 2) {
                rows.add(new ArrayList<>(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) rows.add(row);

        Map<String, Integer> cart = carts.get(chat);
        String cartText = (cart == null || cart.isEmpty()) ? cfg.msg("cart-empty") : cartSummary(cart);
        String text = cfg.msg("shop", "products", String.join("\n", lines), "cart", cartText);

        String cartLabel = cfg.btn("cart") + cartCount(cart);
        rows.add(row2(btn(cartLabel, "cart"), btn(cfg.btn("back"), "prof")));

        if (mid == 0) bot.send(chat, text, rows);
        else bot.edit(chat, mid, text, rows);
    }

    private String cartCount(Map<String, Integer> cart) {
        if (cart == null || cart.isEmpty()) return "";
        int n = 0;
        for (int v : cart.values()) n += v;
        return " (" + n + ")";
    }

    private void buyItem(CallbackQuery q, String key) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        PlayerData p = data.getPlayer(chat);
        if (p == null) {
            showRegionSelect(chat, mid, false);
            return;
        }
        if (!docsAccepted(chat, p)) {
            bot.answer(q.getId(), cfg.msg("agreement-blocked"));
            showAgreement(chat, mid, unacceptedDoc(chat, p));
            return;
        }
        if (activeOrder(p) != null) {
            bot.answer(q.getId(), cfg.msg("active-order", "order", activeOrder(p).id));
            return;
        }
        ShopConfig.Product pr = cfg.product(key);
        if (pr == null) return;
        Map<String, Integer> cart = carts.computeIfAbsent(chat, k -> new LinkedHashMap<>());
        int qty = cart.merge(key, 1, Integer::sum);
        bot.answer(q.getId(), cfg.msg("added-to-cart", "name", pr.name, "quantity", qty));
        openShop(chat, mid, p);
    }

    private void removeFromCart(CallbackQuery q, String key) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        Map<String, Integer> cart = carts.get(chat);
        if (cart != null) {
            cart.remove(key);
            if (cart.isEmpty()) carts.remove(chat);
        }
        openCart(chat, mid, data.getPlayer(chat));
    }

    private void openCart(long chat, int mid, PlayerData p) {
        if (p == null) {
            showRegionSelect(chat, mid, false);
            return;
        }
        Order active = activeOrder(p);
        if (active != null) {
            bot.edit(chat, mid, cfg.msg("active-order", "order", active.id));
            return;
        }
        Map<String, Integer> cart = carts.get(chat);
        List<String> itemLines = new ArrayList<>();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int base = 0;
        if (cart != null) {
            for (Map.Entry<String, Integer> e : cart.entrySet()) {
                ShopConfig.Product pr = cfg.product(e.getKey());
                if (pr == null) continue;
                int sum = pr.price * e.getValue();
                base += sum;
                String line = cfg.msg("cart-item-line", "name", pr.name, "quantity", e.getValue(), "sum", sum);
                itemLines.add(line);
                rows.add(row2(btn(line, "cinfo|" + e.getKey()), btn(cfg.btn("delete"), "crm|" + e.getKey())));
            }
        }

        PromoCode promo = validPromo(p);
        String discountLine = "";
        int total = base;
        if (promo != null) {
            int discounted = base * (100 - promo.percent) / 100;
            int saved = base - discounted;
            total = discounted;
            discountLine = cfg.msg("cart-discount", "promo", promo.name, "percent", promo.percent, "saved", saved);
        }

        String text;
        if (itemLines.isEmpty()) {
            text = cfg.msg("cart-empty");
            rows.clear();
            rows.add(row2(btn(cfg.btn("shop"), "sh"), btn(cfg.btn("back"), "prof")));
        } else {
            text = cfg.msg("cart", "items", String.join("\n", itemLines), "total", total, "discount_line", discountLine);
            rows.add(row2(btn(cfg.btn("pay"), "pay"), btn(cfg.btn("shop"), "sh")));
            rows.add(row1(btn(cfg.btn("back"), "prof")));
        }
        bot.edit(chat, mid, text, rows);
    }

    private void pay(CallbackQuery q) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        PlayerData p = data.getPlayer(chat);
        if (p == null) return;
        Order active = activeOrder(p);
        if (active != null) {
            bot.answer(q.getId(), cfg.msg("active-order", "order", active.id));
            return;
        }
        if (!docsAccepted(chat, p)) {
            bot.answer(q.getId(), cfg.msg("agreement-blocked"));
            showAgreement(chat, mid, unacceptedDoc(chat, p));
            return;
        }
        Map<String, Integer> cart = carts.get(chat);
        if (cart == null || cart.isEmpty()) {
            bot.answer(q.getId(), cfg.msg("cart-empty"));
            return;
        }

        Order o = new Order();
        o.id = genOrderId();
        o.playerId = p.id;
        o.nickname = p.nickname;
        o.username = p.username;
        o.region = p.country;
        o.createdAt = System.currentTimeMillis();
        o.state = Order.PENDING;

        int base = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            ShopConfig.Product pr = cfg.product(e.getKey());
            if (pr == null) continue;
            o.items.add(new OrderItem(e.getKey(), pr.name, pr.price, e.getValue()));
            base += pr.price * e.getValue();
        }
        if (o.items.isEmpty()) {
            bot.answer(q.getId(), cfg.msg("cart-empty"));
            return;
        }

        PromoCode promo = validPromo(p);
        if (promo != null) {
            o.promo = promo.name;
            o.promoPercent = promo.percent;
            o.total = base * (100 - promo.percent) / 100;
        } else {
            o.total = base;
        }

        data.orders().add(o);
        carts.remove(chat);
        data.save();

        if (cfg.paymentEnabled() && paymentService != null) {
            PaymentService.PaymentResult result = paymentService.createPayment(o.id, o.total);
            if (result != null && result.payUrl != null) {
                o.state = Order.PAYING;
                o.paymentId = result.paymentId;
                data.save();
                List<List<InlineKeyboardButton>> payRows = new ArrayList<>();
                payRows.add(row1(urlBtn(cfg.btn("pay-online"), result.payUrl)));
                payRows.add(row1(btn(cfg.btn("back"), "prof")));
                bot.edit(chat, mid, cfg.msg("pay-wait-online", "order", o.id, "total", o.total), payRows);
            } else {
                data.orders().remove(o);
                data.save();
                bot.edit(chat, mid, cfg.msg("pay-error"), rows1(btn(cfg.btn("back"), "prof")));
            }
            return;
        }

        bot.edit(chat, mid, cfg.msg("pay-wait", "order", o.id, "total", o.total), NO_BUTTONS);
        notifyAdmins(o);
    }

    private void notifyAdmins(Order o) {
        String text = orderInfoText(o);
        List<List<InlineKeyboardButton>> rows = rows2(
                btn(cfg.btn("take-order"), "atake|" + o.id),
                btn(cfg.btn("next-order"), "anext|" + o.id));
        for (ShopConfig.Admin a : cfg.admins()) {
            if (a.id <= 0) continue;
            Message m = bot.send(a.id, text, rows);
            if (m != null) o.adminMessages.put(a.id, m.getMessageId());
        }
        data.save();
    }

    private void onPaymentStatus(String orderId, String status) {
        Order o = findOrder(orderId);
        if (o == null) return;

        if (status.equalsIgnoreCase("paid") || status.equalsIgnoreCase("succeeded")) {
            if (!o.state.equals(Order.PAYING)) return;
            o.state = Order.CONFIRMED;
            o.confirmedAt = System.currentTimeMillis();
            runCommands(o);

            PlayerData pd = data.getPlayer(o.playerId);
            if (pd != null) {
                pd.purchaseCount++;
                pd.purchases.add(new PurchaseRecord(itemsText(o), o.total, System.currentTimeMillis()));
                if (o.promo != null && !o.promo.isEmpty()) {
                    PromoCode pr = findPromo(o.promo);
                    if (pr != null && !pr.usedByPlayer(pd.id)) {
                        pr.used++;
                        pr.usedBy.add(pd.id);
                        pd.promoCount++;
                        pd.usedPromos.add(pr.name);
                    }
                    pd.pendingPromo = null;
                }
                bot.send(o.playerId, cfg.msg("pay-success", "order", o.id, "total", o.total));
                showProfile(o.playerId, 0, pd);
            }

            data.stats().add(o);
            data.orders().remove(o);
            data.save();

            String adminText = cfg.msg("admin-order-auto-confirmed",
                    "order", o.id, "nick", o.nickname, "total", o.total);
            for (ShopConfig.Admin a : cfg.admins()) {
                if (a.id <= 0) continue;
                bot.send(a.id, adminText);
            }
        } else if (status.equalsIgnoreCase("failed") || status.equalsIgnoreCase("cancelled")) {
            if (!o.state.equals(Order.PAYING)) return;
            o.state = Order.CANCELLED;
            data.orders().remove(o);
            data.save();

            PlayerData pd = data.getPlayer(o.playerId);
            if (pd != null) {
                bot.send(o.playerId, cfg.msg("pay-failed", "order", o.id));
                showProfile(o.playerId, 0, pd);
            }
        }
    }

    //  Заказы

    private void takeOrder(CallbackQuery q, String id) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        String username = q.getFrom().getUserName();
        Order o = findOrder(id);
        if (o == null) {
            bot.answer(q.getId(), cfg.msg("order-notfound"));
            return;
        }
        if (o.state.equals(Order.TAKEN)) {
            if (o.takenBy != null && o.takenBy == chat) bot.answer(q.getId(), cfg.msg("order-already-taken"));
            else bot.answer(q.getId(), cfg.msg("admin-order-taken-by-other", "order", o.id, "admin", adminNameOf(o.takenBy)));
            return;
        }
        o.state = Order.TAKEN;
        o.takenBy = chat;
        data.save();

        PlayerData pd = data.getPlayer(o.playerId);
        if (pd != null) {
            bot.send(o.playerId, cfg.msg("order-taken-player", "admin", usernameText(username)));
        }

        List<List<InlineKeyboardButton>> rows = combine(controls(o, chat), rows1(btn(cfg.btn("exit-menu"), "am")));
        bot.edit(chat, mid, orderInfoText(o) + statusText(o, chat), rows);

        for (Map.Entry<Long, Integer> e : o.adminMessages.entrySet()) {
            if (e.getKey() == chat) continue;
            bot.edit(e.getKey(), e.getValue(),
                    orderInfoText(o) + statusText(o, chat),
                    rows1(btn(cfg.btn("admin-menu"), "am")));
        }
    }

    private void nextOrder(CallbackQuery q, String id) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        List<Order> active = activeOrders();
        if (active.isEmpty()) {
            bot.edit(chat, mid, cfg.msg("orders-none"), rows1(btn(cfg.btn("exit-menu"), "am")));
            return;
        }
        int idx = 0;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).id != null && active.get(i).id.equals(id)) {
                idx = i;
                break;
            }
        }
        Order next = active.get((idx + 1) % active.size());
        List<List<InlineKeyboardButton>> rows = combine(controls(next, chat), rows1(btn(cfg.btn("exit-menu"), "am")));
        bot.edit(chat, mid, orderInfoText(next) + statusText(next, chat), rows);
    }

    private void showOrder(CallbackQuery q, String id, String page) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        Order o = findOrder(id);
        if (o == null) {
            showActiveOrders(chat, mid, 1);
            return;
        }
        List<List<InlineKeyboardButton>> rows = combine(controls(o, chat),
                rows2(btn(cfg.btn("back"), "aols|" + page), btn(cfg.btn("exit-menu"), "am")));
        bot.edit(chat, mid, orderInfoText(o) + statusText(o, chat), rows);
    }

    private void showActiveOrders(long chat, int mid, int page) {
        List<Order> active = activeOrders();
        if (active.isEmpty()) {
            String text = cfg.msg("orders-none");
            if (mid == 0) bot.send(chat, text, rows1(btn(cfg.btn("exit-menu"), "am")));
            else bot.edit(chat, mid, text, rows1(btn(cfg.btn("exit-menu"), "am")));
            return;
        }
        int pages = (active.size() + ORDERS_PER_PAGE - 1) / ORDERS_PER_PAGE;
        page = Math.max(1, Math.min(page, pages));
        int from = (page - 1) * ORDERS_PER_PAGE;
        int to = Math.min(from + ORDERS_PER_PAGE, active.size());

        List<String> lines = new ArrayList<>();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Order o = active.get(i);
            lines.add(cfg.msg("orders-active-line", "order", o.id, "nick", o.nickname, "items", itemsText(o), "total", o.total));
            rows.add(row1(btn(cfg.btn("order-view") + " #" + o.id + " - " + o.nickname, "aord|" + o.id + "|" + page)));
        }

        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 1) nav.add(btn(cfg.btn("prev-page"), "aols|" + (page - 1)));
        nav.add(btn(cfg.btn("exit-menu"), "am"));
        if (page < pages) nav.add(btn(cfg.btn("next-page"), "aols|" + (page + 1)));
        rows.add(nav);

        String text = cfg.msg("orders-active", "list", String.join("\n", lines))
                + "\n\n" + cfg.msg("order-page", "page", page, "pages", pages);
        if (mid == 0) bot.send(chat, text, rows);
        else bot.edit(chat, mid, text, rows);
    }

    private void adminReplyStart(CallbackQuery q, String id) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        Order o = findOrder(id);
        if (o == null || !o.state.equals(Order.TAKEN) || o.takenBy == null || o.takenBy != chat) {
            bot.answer(q.getId(), cfg.msg("order-not-yours"));
            return;
        }
        states.put(chat, "REPLY");
        replyOrders.put(chat, id);
        bot.edit(chat, mid, cfg.msg("admin-write-prompt", "nick", o.nickname), rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    private void handleAdminReply(Message m) {
        long chat = m.getChatId();
        String id = replyOrders.get(chat);
        Order o = findOrder(id);
        states.remove(chat);
        replyOrders.remove(chat);
        if (o == null) {
            showAdminMenu(chat, 0);
            return;
        }
        PlayerData pd = data.getPlayer(o.playerId);
        if (pd != null) {
            bot.send(o.playerId, cfg.msg("player-message-from-admin", "text", m.getText()));
        }
        bot.send(chat, cfg.msg("admin-write-sent", "nick", o.nickname));
        List<List<InlineKeyboardButton>> rows = combine(controls(o, chat), rows1(btn(cfg.btn("exit-menu"), "am")));
        bot.send(chat, orderInfoText(o) + statusText(o, chat), rows);
    }

    private void confirmPayment(CallbackQuery q, String id) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        String username = q.getFrom().getUserName();
        Order o = findOrder(id);
        if (o == null) {
            bot.answer(q.getId(), cfg.msg("order-notfound"));
            return;
        }
        if (!o.state.equals(Order.TAKEN) || o.takenBy == null || o.takenBy != chat) {
            bot.answer(q.getId(), cfg.msg("order-not-yours"));
            return;
        }

        runCommands(o);

        PlayerData pd = data.getPlayer(o.playerId);
        if (pd != null) {
            pd.purchaseCount++;
            pd.purchases.add(new PurchaseRecord(itemsText(o), o.total, System.currentTimeMillis()));
            if (o.promo != null && !o.promo.isEmpty()) {
                PromoCode pr = findPromo(o.promo);
                if (pr != null && !pr.usedByPlayer(pd.id)) {
                    pr.used++;
                    pr.usedBy.add(pd.id);
                    pd.promoCount++;
                    pd.usedPromos.add(pr.name);
                }
                pd.pendingPromo = null;
            }
            bot.send(o.playerId, cfg.msg("thanks-order"));
            showProfile(o.playerId, 0, pd);
        }

        o.state = Order.CONFIRMED;
        o.confirmedAt = System.currentTimeMillis();
        data.stats().add(o);
        data.orders().remove(o);
        data.save();

        showActiveOrders(chat, mid, 1);

        for (Map.Entry<Long, Integer> e : o.adminMessages.entrySet()) {
            if (e.getKey() == chat) continue;
            bot.edit(e.getKey(), e.getValue(),
                    cfg.msg("order-processed-admin", "order", o.id, "admin", usernameText(username)),
                    rows1(btn(cfg.btn("admin-menu"), "am")));
        }
    }

    private void cancelPayment(CallbackQuery q, String id) {
        long chat = q.getMessage().getChatId();
        int mid = q.getMessage().getMessageId();
        String username = q.getFrom().getUserName();
        Order o = findOrder(id);
        if (o == null) {
            bot.answer(q.getId(), cfg.msg("order-notfound"));
            return;
        }
        if (!o.state.equals(Order.TAKEN) || o.takenBy == null || o.takenBy != chat) {
            bot.answer(q.getId(), cfg.msg("order-not-yours"));
            return;
        }

        PlayerData pd = data.getPlayer(o.playerId);
        if (pd != null) {
            bot.send(o.playerId, cfg.msg("order-cancelled", "order", o.id));
            showProfile(o.playerId, 0, pd);
        }

        o.state = Order.CANCELLED;
        data.orders().remove(o);
        data.save();

        showActiveOrders(chat, mid, 1);

        for (Map.Entry<Long, Integer> e : o.adminMessages.entrySet()) {
            if (e.getKey() == chat) continue;
            bot.edit(e.getKey(), e.getValue(),
                    cfg.msg("order-processed-admin", "order", o.id, "admin", usernameText(username)),
                    rows1(btn(cfg.btn("admin-menu"), "am")));
        }
    }

    private void runCommands(Order o) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (OrderItem it : o.items) {
                ShopConfig.Product pr = cfg.product(it.key);
                if (pr == null || pr.command == null || pr.command.trim().isEmpty()) {
                    plugin.getLogger().warning("Для товара " + it.name + " не настроена команда выдачи!");
                    continue;
                }
                String cmd = pr.command.replace("{player}", o.nickname);
                for (int i = 0; i < it.quantity; i++) {
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Ошибка выполнения команды [" + cmd + "]: " + e.getMessage());
                    }
                }
            }
        });
    }

    //  Промокоды

    private void enterPromo(long chat, int mid) {
        PlayerData p = data.getPlayer(chat);
        if (p == null) {
            showRegionSelect(chat, mid, false);
            return;
        }
        if (activeOrder(p) != null) {
            bot.edit(chat, mid, cfg.msg("active-order", "order", activeOrder(p).id));
            return;
        }
        states.put(chat, "PROMO");
        bot.edit(chat, mid, cfg.msg("promo-enter"), rows1(btn(cfg.btn("back"), "prof")));
    }

    private void handlePromoInput(Message m) {
        long chat = m.getChatId();
        PlayerData p = data.getPlayer(chat);
        if (p == null) {
            states.remove(chat);
            onStart(chat, m.getFrom().getUserName());
            return;
        }
        if (activeOrder(p) != null) {
            states.remove(chat);
            bot.send(chat, cfg.msg("active-order", "order", activeOrder(p).id));
            return;
        }
        String name = m.getText().trim();
        PromoCode pr = findPromo(name);
        if (pr == null) {
            bot.send(chat, cfg.msg("promo-not-found"));
            return;
        }
        if (pr.isExpired()) {
            bot.send(chat, cfg.msg("promo-expired"));
            return;
        }
        if (!pr.hasActivations()) {
            bot.send(chat, cfg.msg("promo-exhausted"));
            return;
        }
        if (pr.usedByPlayer(p.id)) {
            bot.send(chat, cfg.msg("promo-already-used"));
            return;
        }
        states.remove(chat);
        p.pendingPromo = pr.name;
        data.save();
        bot.send(chat, cfg.msg("promo-activated", "promo", pr.name, "percent", pr.percent));
        showProfile(chat, 0, p);
    }

    //  Админ-панель

    private void showAdminMenu(long chat, int mid) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row2(btn(cfg.btn("analytics"), "ast"), btn(cfg.btn("create-promo"), "acp")));
        rows.add(row2(btn(cfg.btn("promo-list"), "apl"), btn(cfg.btn("broadcast"), "abr")));
        rows.add(row2(btn(cfg.btn("ban"), "aban"), btn(cfg.btn("banned-list"), "abal")));
        if (mid == 0) bot.send(chat, cfg.msg("admin-menu"), rows);
        else bot.edit(chat, mid, cfg.msg("admin-menu"), rows);
    }

    // Аналитика 

    private void analyticsMenu(long chat, int mid) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row2(btn(cfg.btn("analytics-1m"), "asp|1"), btn(cfg.btn("analytics-3m"), "asp|3")));
        rows.add(row2(btn(cfg.btn("analytics-9m"), "asp|9"), btn(cfg.btn("analytics-1y"), "asp|12")));
        rows.add(row1(btn(cfg.btn("analytics-all"), "asp|all")));
        rows.add(row1(btn(cfg.btn("back"), "am")));
        if (mid == 0) bot.send(chat, cfg.msg("analytics-period"), rows);
        else bot.edit(chat, mid, cfg.msg("analytics-period"), rows);
    }

    private void analyticsResult(long chat, int mid, String period) {
        long now = System.currentTimeMillis();
        long cutoff = 0;
        String label;
        switch (period) {
            case "1":
                cutoff = now - 30L * 86400000L;
                label = cfg.msg("period-1m");
                break;
            case "3":
                cutoff = now - 90L * 86400000L;
                label = cfg.msg("period-3m");
                break;
            case "9":
                cutoff = now - 270L * 86400000L;
                label = cfg.msg("period-9m");
                break;
            case "12":
                cutoff = now - 365L * 86400000L;
                label = cfg.msg("period-1y");
                break;
            default:
                label = cfg.msg("period-all");
        }
        long amount = 0;
        int count = 0;
        for (Order o : data.stats()) {
            if (o.state.equals(Order.CONFIRMED) && o.confirmedAt >= cutoff) {
                amount += o.total;
                count++;
            }
        }
        String text = count > 0
                ? cfg.msg("analytics-result", "period", label, "amount", amount, "count", count)
                : cfg.msg("analytics-none", "period", label);
        bot.edit(chat, mid, text, rows1(btn(cfg.btn("back"), "am")));
    }

    // Промокоды

    private void promoCreateStart(long chat, int mid) {
        states.put(chat, "PROMO_NAME");
        promoDrafts.put(chat, new PromoDraft());
        bot.edit(chat, mid, cfg.msg("promo-create-name"), rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    private void handlePromoName(Message m) {
        long chat = m.getChatId();
        PromoDraft draft = promoDrafts.get(chat);
        if (draft == null) return;
        String name = m.getText().trim();
        if (!PROMO_NAME_PATTERN.matcher(name).matches()) {
            bot.send(chat, cfg.msg("promo-invalid-format"));
            return;
        }
        if (findPromo(name) != null) {
            bot.send(chat, cfg.msg("promo-create-name-taken"));
            return;
        }
        draft.name = name;
        states.put(chat, "PROMO_PERCENT");
        bot.send(chat, cfg.msg("promo-create-percent"));
    }

    private void handlePromoPercent(Message m) {
        long chat = m.getChatId();
        PromoDraft draft = promoDrafts.get(chat);
        if (draft == null) return;
        try {
            int pct = Integer.parseInt(m.getText().trim());
            if (pct < 1 || pct > 100) {
                bot.send(chat, cfg.msg("promo-invalid-format"));
                return;
            }
            draft.percent = pct;
        } catch (NumberFormatException e) {
            bot.send(chat, cfg.msg("promo-invalid-format"));
            return;
        }
        states.put(chat, "PROMO_ACTIVATIONS");
        bot.send(chat, cfg.msg("promo-create-activations"));
    }

    private void handlePromoActivations(Message m) {
        long chat = m.getChatId();
        PromoDraft draft = promoDrafts.get(chat);
        if (draft == null) return;
        String t = m.getText().trim();
        if (t.equals("-")) {
            draft.activations = -1;
        } else {
            try {
                long act = Long.parseLong(t);
                if (act < 1) {
                    bot.send(chat, cfg.msg("promo-invalid-format"));
                    return;
                }
                draft.activations = act;
            } catch (NumberFormatException e) {
                bot.send(chat, cfg.msg("promo-invalid-format"));
                return;
            }
        }
        states.put(chat, "PROMO_DURATION");
        bot.send(chat, cfg.msg("promo-create-duration"));
    }

    private void handlePromoDuration(Message m) {
        long chat = m.getChatId();
        PromoDraft draft = promoDrafts.get(chat);
        if (draft == null) return;
        String t = m.getText().trim();
        Matcher mm = DURATION_PATTERN.matcher(t);
        if (!mm.matches()) {
            bot.send(chat, cfg.msg("promo-invalid-format"));
            return;
        }
        long dur = Long.parseLong(mm.group(1)) * 86400000L
                + Long.parseLong(mm.group(2)) * 3600000L
                + Long.parseLong(mm.group(3)) * 60000L;
        if (dur <= 0) dur = 86400000L;

        PromoCode pr = new PromoCode(draft.name, draft.percent, draft.activations, System.currentTimeMillis() + dur);
        data.promos().add(pr);
        states.remove(chat);
        promoDrafts.remove(chat);
        data.save();

        String actText = pr.maxActivations < 0 ? "∞" : String.valueOf(pr.maxActivations);
        bot.send(chat, cfg.msg("promo-create-done",
                "promo", pr.name,
                "percent", pr.percent,
                "activations", actText,
                "duration", t), rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    // Список промокодов

    private void promoList(long chat, int mid) {
        List<PromoCode> valid = new ArrayList<>();
        for (PromoCode pr : data.promos()) {
            if (!pr.isExpired()) valid.add(pr);
        }
        if (valid.isEmpty()) {
            bot.edit(chat, mid, cfg.msg("promo-list-empty"), rows1(btn(cfg.btn("exit-menu"), "am")));
            return;
        }
        List<String> lines = new ArrayList<>();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (PromoCode pr : valid) {
            String left = pr.left() < 0 ? "∞" : String.valueOf(pr.left());
            String line = cfg.msg("promo-list-line", "name", pr.name, "percent", pr.percent, "left", left);
            lines.add(line);
            rows.add(row2(btn(line, "pinfo|" + pr.name), btn(cfg.btn("delete"), "apd|" + pr.name)));
        }
        rows.add(row1(btn(cfg.btn("exit-menu"), "am")));
        bot.edit(chat, mid, cfg.msg("promo-list", "list", String.join("\n", lines)), rows);
    }

    private void promoDeleteConfirm(long chat, int mid, String name) {
        bot.edit(chat, mid,
                cfg.msg("promo-delete-confirm", "promo", name),
                rows2(btn(cfg.btn("yes"), "apdy|" + name), btn(cfg.btn("no"), "apdn")));
    }

    private void promoDelete(long chat, int mid, String name) {
        PromoCode pr = findPromo(name);
        if (pr != null) {
            data.promos().remove(pr);
            data.save();
            bot.send(chat, cfg.msg("promo-deleted", "promo", name));
        }
        promoList(chat, mid);
    }

    // Оповищение

    private void broadcastStart(long chat, int mid) {
        states.put(chat, "BROADCAST");
        bot.edit(chat, mid, cfg.msg("broadcast-prompt"), rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    private void handleBroadcastInput(Message m) {
        long chat = m.getChatId();
        String text = m.getText();
        states.remove(chat);
        broadcastDrafts.put(chat, text);
        bot.send(chat, cfg.msg("broadcast-confirm", "text", text),
                rows2(btn(cfg.btn("yes"), "abry"), btn(cfg.btn("no"), "abrn")));
    }

    private void broadcastSend(long chat, int mid) {
        String draft = broadcastDrafts.remove(chat);
        if (draft == null) {
            showAdminMenu(chat, mid);
            return;
        }
        int count = 0;
        for (PlayerData pd : data.allPlayers()) {
            bot.send(pd.id, cfg.msg("broadcast-user", "text", draft));
            count++;
        }
        bot.edit(chat, mid, cfg.msg("broadcast-done", "count", count), rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    private void broadcastCancel(long chat, int mid) {
        broadcastDrafts.remove(chat);
        showAdminMenu(chat, mid);
    }

    // Баны

    private void banStart(long chat, int mid) {
        states.put(chat, "BAN");
        List<String> lines = new ArrayList<>();
        for (PlayerData pd : data.allPlayers()) {
            String u = (pd.username == null || pd.username.isEmpty()) ? "id:" + pd.id : "@" + pd.username;
            lines.add(cfg.msg("ban-user-line", "username", u, "nick", pd.nickname));
        }
        String text = lines.isEmpty()
                ? cfg.msg("ban-prompt", "list", "-")
                : cfg.msg("ban-prompt", "list", String.join("\n", lines));
        bot.edit(chat, mid, text, rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    private void handleBanInput(Message m) {
        long chat = m.getChatId();
        String u = m.getText().trim();
        if (u.startsWith("@")) u = u.substring(1);
        PlayerData pd = data.getPlayerByUsername(u);
        if (pd == null) {
            bot.send(chat, cfg.msg("ban-notfound", "username", u));
            return;
        }
        if (data.isBanned(pd.username)) {
            states.remove(chat);
            bot.send(chat, cfg.msg("ban-already", "username", u));
            return;
        }
        data.banned().add(new BannedUser(pd.username.isEmpty() ? u : pd.username, pd.id));
        states.remove(chat);
        data.save();
        bot.send(pd.id, cfg.msg("banned"));
        String shown = (pd.username == null || pd.username.isEmpty()) ? u : "@" + pd.username;
        bot.send(chat, cfg.msg("ban-done", "username", shown, "nick", pd.nickname));
    }

    private void bannedList(long chat, int mid) {
        states.put(chat, "UNBAN");
        if (data.banned().isEmpty()) {
            bot.edit(chat, mid, cfg.msg("banned-empty"), rows1(btn(cfg.btn("exit-menu"), "am")));
            return;
        }
        List<String> lines = new ArrayList<>();
        for (BannedUser b : data.banned()) {
            String nick = "-";
            if (b.id > 0) {
                PlayerData pd = data.getPlayer(b.id);
                if (pd != null) nick = pd.nickname;
            }
            String u = b.username.startsWith("@") ? b.username : "@" + b.username;
            lines.add(cfg.msg("ban-user-line", "username", u, "nick", nick));
        }
        bot.edit(chat, mid,
                cfg.msg("banned-list", "list", String.join("\n", lines)),
                rows1(btn(cfg.btn("exit-menu"), "am")));
    }

    private void handleUnbanInput(Message m) {
        long chat = m.getChatId();
        String u = m.getText().trim();
        if (u.startsWith("@")) u = u.substring(1);
        BannedUser bu = data.findBanned(u);
        if (bu == null) {
            bot.send(chat, cfg.msg("unban-notfound", "username", u));
            return;
        }
        data.banned().remove(bu);
        states.remove(chat);
        data.save();
        bot.send(chat, cfg.msg("unban-done", "username", "@" + u));
    }

    //  Черновик промокода

    private static class PromoDraft {
        String name;
        int percent;
        long activations = -1;
    }
}
