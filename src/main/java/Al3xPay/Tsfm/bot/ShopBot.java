package Al3xPay.Tsfm.bot;

import Al3xPay.Tsfm.Tsfm;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

public class ShopBot extends TelegramLongPollingBot {

    private final Tsfm plugin;
    private final Flow flow;

    public ShopBot(Tsfm plugin) {
        this.plugin = plugin;
        this.flow = new Flow(plugin, this);
    }

    public Flow getFlow() {
        return flow;
    }

    @Override
    public String getBotUsername() {
        return plugin.getShopConfig().getBotUsername();
    }

    @Override
    public String getBotToken() {
        return plugin.getShopConfig().getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            flow.onCallback(update.getCallbackQuery());
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            flow.onMessage(update.getMessage());
        }
    }

    public Message send(long chatId, String text) {
        return send(chatId, text, null);
    }

    public Message send(long chatId, String text, List<List<InlineKeyboardButton>> rows) {
        SendMessage sm = new SendMessage();
        sm.setChatId(String.valueOf(chatId));
        sm.setText(truncate(text));
        if (rows != null && !rows.isEmpty()) sm.setReplyMarkup(keyboard(rows));
        try {
            return execute(sm);
        } catch (TelegramApiException e) {
            return null;
        }
    }

    public void edit(long chatId, int messageId, String text) {
        edit(chatId, messageId, text, null);
    }

    public void edit(long chatId, int messageId, String text, List<List<InlineKeyboardButton>> rows) {
        EditMessageText em = new EditMessageText();
        em.setChatId(String.valueOf(chatId));
        em.setMessageId(messageId);
        em.setText(truncate(text));
        if (rows != null) em.setReplyMarkup(keyboard(rows));
        try {
            execute(em);
        } catch (TelegramApiException ignored) {
        }
    }

    public void answer(String callbackId, String text) {
        AnswerCallbackQuery a = new AnswerCallbackQuery();
        a.setCallbackQueryId(callbackId);
        if (text != null && !text.isEmpty()) a.setText(text);
        try {
            execute(a);
        } catch (TelegramApiException ignored) {
        }
    }

    private static InlineKeyboardMarkup keyboard(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() > 4090) return text.substring(0, 4090) + "...";
        return text;
    }
}
