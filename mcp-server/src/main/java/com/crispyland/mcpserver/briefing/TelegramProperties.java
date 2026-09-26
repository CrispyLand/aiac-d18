package com.crispyland.mcpserver.briefing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two strings that identify a Telegram destination.
 * <p>
 * Both are blank by default: an installation with no Telegram config still collects and narrates,
 * and the notifier silently becomes a no-op. That is the same pattern as the narrator's
 * {@code api-key} — a missing credential degrades one step, it does not stop the job.
 *
 * @param botToken  the HTTP API token, from @BotFather — the part after {@code bot} in the URL
 * @param chatId    the conversation or channel to post to. The numeric id the bot received a
 *                  message from, or a channel username prefixed with {@code @}
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String chatId) {

    public TelegramProperties {
        botToken = (botToken == null) ? "" : botToken.strip();
        chatId   = (chatId   == null) ? "" : chatId.strip();
    }

    public boolean configured() {
        return !botToken.isEmpty() && !chatId.isEmpty();
    }
}
