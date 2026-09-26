package com.crispyland.mcpserver.briefing;

import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One Groq call, over figures that are already final.
 * <p>
 * Deliberately not a reuse of the agent's {@code GroqLlmClient}: that is a different Maven project,
 * and most of its length is tool-call plumbing this has no use for. What is copied is the request
 * shape and the one decision worth copying — disable the default throw-on-error so the status and
 * body can be read rather than guessed at.
 * <p>
 * Everything here is arranged around a single rule: <b>this method cannot cost a collection.</b> The
 * numbers were computed before it was called and are written after it returns, and every failure
 * path — no key, unreachable host, 429, 500, empty choices, malformed JSON — ends in {@code ""} and a
 * log line. That is why the {@code catch} is on {@link Exception} rather than on the three or four
 * types actually expected: the list of ways an HTTP client can fail is not a list worth maintaining
 * when every entry has the same answer, and an unlisted one would take the scheduled job down.
 */
public class GroqBriefingNarrator implements BriefingNarrator {

    private static final Logger log = LoggerFactory.getLogger(GroqBriefingNarrator.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    /**
     * The whole instruction. Every line is a failure observed from a model handed a table of numbers:
     * it greets the reader, it offers to help with time management, or it decides four meetings is "a
     * packed day". The figures are printed on the card directly above the sentence, so inventing one
     * is immediately visible.
     * <p>
     * Two of the rules exist because of inventions that are <em>not</em> visible that way. Asked for
     * the third person it guessed a gender ("Today she has one all-day event") — a fact about the
     * reader that no figure supplied — so the sentence is written in the second person, which needs no
     * pronoun for them at all. And it called a day three days past "Today", because a model shown only
     * a date assumes the date is now; the briefing is re-collectable for any day, so the date has to be
     * named rather than implied.
     */
    private static final String SYSTEM = """
            You write a one or two sentence summary of a day from figures that have already been \
            computed. Write plainly and in the present tense, addressing the person whose day it is \
            as "you".
            Rules:
            - Use only the figures and titles given. Never invent an event, a task, or a number.
            - Say nothing about the person that the figures do not state — not their gender, their \
            mood, or how their day is going.
            - The date given is the day being described, and it is not necessarily today. Name it or \
            say nothing about when it is; never call it "today".
            - No greeting, no sign-off, no advice, no questions, no markdown, no bullet points.
            - Two sentences at most. A quiet day deserves a short sentence, not a consolation.""";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final BriefingProperties.Narrator settings;

    public GroqBriefingNarrator(RestClient restClient, ObjectMapper mapper,
                                BriefingProperties.Narrator settings) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public String narrate(Briefing briefing) {
        if (!settings.hasKey()) {
            // Not an error and not worth a stack trace: a deployment without the key still collects.
            log.debug("No narrator API key set — storing {} without a sentence.", briefing.date());
            return "";
        }
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(settings.endpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(request(briefing)))
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Narrator got HTTP {} from {} — storing {} without a sentence.",
                        response.getStatusCode().value(), settings.endpoint(), briefing.date());
                return "";
            }
            String sentence = content(response.getBody());
            if (sentence.isEmpty()) {
                // finish_reason is the whole diagnosis: "length" means the budget went on hidden
                // reasoning tokens before a word was written, which is a settings problem and not a
                // provider one. Without it in the log this failure looks like a quiet day.
                log.warn("Narrator returned no usable content (finish_reason={}, model={},"
                                + " max_tokens={}, reasoning_effort='{}') — storing {} without a"
                                + " sentence.", finishReason(response.getBody()), settings.model(),
                        settings.maxTokens(), settings.reasoningEffort(), briefing.date());
            }
            return sentence;
        } catch (Exception e) {
            log.warn("Narrator failed ({}: {}) — storing {} without a sentence.",
                    e.getClass().getSimpleName(), e.getMessage(), briefing.date());
            return "";
        }
    }

    private ObjectNode request(Briefing briefing) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", settings.model());
        root.put("temperature", settings.temperature());
        root.put("max_tokens", settings.maxTokens());
        // Omitted rather than sent empty when unset, because the models that need it absent reject
        // the parameter outright rather than ignoring it.
        if (!settings.reasoningEffort().isEmpty()) {
            root.put("reasoning_effort", settings.reasoningEffort());
        }

        ArrayNode messages = root.putArray("messages");
        message(messages, "system", SYSTEM);
        message(messages, "user", prompt(briefing));
        return root;
    }

    private void message(ArrayNode messages, String role, String content) {
        ObjectNode node = messages.addObject();
        node.put("role", role);
        node.put("content", content);
    }

    /**
     * The figures, then the lines. The highlights are already capped by
     * {@code Briefings.MAX_HIGHLIGHTS}, which is what keeps this prompt a fixed size no matter how
     * busy the day was — a cost control, not a display one.
     */
    private String prompt(Briefing briefing) {
        StringBuilder out = new StringBuilder(256)
                .append(briefing.date().format(DAY)).append('\n')
                .append(briefing.figures());
        if (!briefing.highlights().isEmpty()) {
            out.append("\n\nThe day:");
            briefing.highlights().forEach(line -> out.append("\n- ").append(line));
        }
        return out.toString();
    }

    /** {@code choices[0].message.content}, or {@code ""} if the reply is not that shape. */
    private String content(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        JsonNode message = mapper.readTree(body).path("choices").path(0).path("message");
        String content = message.path("content").stringValue("");
        return content.strip();
    }

    /** Why the reply had no content. Only ever read on the path where there is nothing to store. */
    private String finishReason(String body) {
        return mapper.readTree(body).path("choices").path(0).path("finish_reason")
                .stringValue("unknown");
    }
}
