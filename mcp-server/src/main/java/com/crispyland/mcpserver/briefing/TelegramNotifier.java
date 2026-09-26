package com.crispyland.mcpserver.briefing;

import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends a collected briefing to a Telegram chat.
 * <p>
 * The contract is the same as {@link BriefingNarrator}'s: <b>this method never throws.</b> It is
 * called from the collector's single scheduler thread, and an exception escaping there would
 * silently end the scheduled job. Every failure — no token, unreachable server, 400 from Telegram,
 * malformed JSON — ends in a log line and a return.
 * <p>
 * Not configured is not an error: the {@link #NONE} sentinel is used when either token or chat id
 * is absent, and the method becomes a no-op. A deployment that has no Telegram setup still collects
 * and narrates; it just does not deliver.
 */
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    /** Used when Telegram is not configured — no call is made, no log line is written. */
    public static final TelegramNotifier NONE = new TelegramNotifier(null, null, null);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final TelegramProperties properties;

    public TelegramNotifier(RestClient restClient, ObjectMapper mapper,
                            TelegramProperties properties) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Post the briefing to Telegram. Returns immediately if not configured or if narration
     * produced no sentence — the figures alone are not worth a notification, because the card on
     * the page already shows them.
     */
    public void send(Briefing briefing) {
        if (properties == null || !properties.configured()) {
            return;
        }
        if (!briefing.narrated()) {
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + properties.botToken() + "/sendMessage";
            ObjectNode body = mapper.createObjectNode();
            body.put("chat_id", properties.chatId());
            body.put("text", message(briefing));

            ResponseEntity<String> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Telegram: sent briefing for {}.", briefing.date());
            } else {
                log.warn("Telegram: got HTTP {} for {} — message not delivered.",
                        response.getStatusCode().value(), briefing.date());
            }
        } catch (Exception e) {
            log.warn("Telegram: failed to send briefing for {} ({}: {}) — continuing.",
                    briefing.date(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * The message text. Date and figures on the first line so the notification preview is useful
     * even if the chat app collapses the body; the narrative as the second paragraph; then the
     * highlights — the same order as the card on the page.
     */
    private String message(Briefing briefing) {
        StringBuilder out = new StringBuilder(512)
                .append(briefing.date().format(DAY)).append('\n')
                .append(briefing.figures())
                .append("\n\n")
                .append(briefing.narrative());
        if (!briefing.highlights().isEmpty()) {
            out.append('\n');
            briefing.highlights().forEach(line -> out.append('\n').append(line));
        }
        return out.toString();
    }
}
