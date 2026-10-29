package Al3xPay.Tsfm;

import Al3xPay.Tsfm.bot.ShopBot;
import Al3xPay.Tsfm.config.ShopConfig;
import Al3xPay.Tsfm.data.DataManager;
import Al3xPay.Tsfm.data.PromoCode;
import Al3xPay.Tsfm.payment.PaymentService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

public class Tsfm extends JavaPlugin {

    private static Tsfm instance;

    private ShopConfig shopConfig;
    private DataManager dataManager;
    private ShopBot bot;
    private BotSession botSession;
    private TelegramBotsApi botsApi;
    private PaymentService paymentService;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        shopConfig = new ShopConfig(this);
        shopConfig.reload();

        dataManager = new DataManager(this);
        dataManager.load();

        loadConfigPromocodes();

        startPaymentService();
        startBot();

        getCommand("tsfm").setExecutor(this);
        getLogger().info("Tsfm включен!");
    }

    @Override
    public void onDisable() {
        stopBot();
        if (dataManager != null) dataManager.save();
        getLogger().info("Tsfm выключен.");
    }

    private void startPaymentService() {
        if (!shopConfig.paymentEnabled()) {
            paymentService = null;
            return;
        }
        paymentService = new PaymentService(
                getLogger(),
                shopConfig.terminalId(),
                shopConfig.apiKey(),
                shopConfig.webhookSecret(),
                shopConfig.apiUrl(),
                shopConfig.callbackUrl(),
                shopConfig.successUrl(),
                shopConfig.failUrl(),
                shopConfig.paymentSupportUrl(),
                shopConfig.paymentSandbox()
        );
        if (bot != null) bot.getFlow().setPaymentService(paymentService);
        String mode = shopConfig.paymentSandbox() ? "SANDBOX (тест)" : "PROD (реальные деньги)";
        getLogger().info("Интеграция с платёжным провайдером включена. Режим: " + mode);
    }

    private void startBot() {
        try {
            stopBot();
            Thread.sleep(1500);
            bot = new ShopBot(this);
            if (paymentService != null) bot.getFlow().setPaymentService(paymentService);
            if (botsApi == null) botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botSession = botsApi.registerBot(bot);
            getLogger().info("Бот подключен к Telegram!");
        } catch (Exception e) {
            bot = null;
            getLogger().severe("Не удалось подключить Telegram бота! Проверьте токен в config.yml");
            getLogger().severe(e.getMessage());
        }
    }

    private void stopBot() {
        if (botSession != null) {
            try {
                botSession.stop();
            } catch (Exception ignored) {
            }
            botSession = null;
        }
        bot = null;
    }

    private void loadConfigPromocodes() {
        List<PromoCode> initial = shopConfig.initialPromos();
        if (initial == null || initial.isEmpty()) return;
        boolean changed = false;
        for (PromoCode pr : initial) {
            if (dataManager.findPromo(pr.name) == null) {
                dataManager.promos().add(pr);
                changed = true;
            }
        }
        if (changed) dataManager.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tsfm.reload") && !sender.isOp()) {
            sender.sendMessage("§cУ вас нет прав!");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            shopConfig.reload();
            dataManager.load();
            loadConfigPromocodes();
            startPaymentService();
            startBot();
            sender.sendMessage("§aTsfm перезагружен!");
            return true;
        }
        sender.sendMessage("§eИспользование: /tsfm reload");
        return true;
    }

    public static Tsfm getInstance() {
        return instance;
    }

    public ShopConfig getShopConfig() {
        return shopConfig;
    }

    public DataManager getData() {
        return dataManager;
    }

    public ShopBot getBot() {
        return bot;
    }
}
