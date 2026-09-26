package com.crispyland.web;

import com.crispyland.briefing.BriefingClient;
import com.crispyland.briefing.BriefingPanel;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The briefing card, and the two buttons under it.
 * <p>
 * A {@link ControllerAdvice}, exactly like {@code McpController} and for the same reason: the card is
 * a bystander on the chat page and should not be able to break a chat turn by being wired into the
 * method that serves one. It also means this whole feature can be deleted without touching
 * {@code ChatController}.
 */
@Controller
@ControllerAdvice
public class BriefingController {

    private final BriefingClient client;

    public BriefingController(BriefingClient client) {
        this.client = client;
    }

    /**
     * One HTTP call to the MCP server per rendered view — including the POST that answers a chat turn.
     * <p>
     * Accepted rather than worked around, for consistency with the MCP panel: a GET-only version
     * blanks the card on exactly the request the user looks at most. The mitigations are a short read
     * timeout and a client that cannot throw, so the worst case is a card that says "unreachable"
     * beside a chat reply that arrived normally.
     */
    @ModelAttribute("briefing")
    public BriefingPanel panel() {
        return client.panel();
    }

    /**
     * Both of these redirect rather than render, so the card the user then sees comes from a fresh
     * {@link #panel()} call. That is one extra round trip and it is worth it: rendering the response of
     * the POST directly would show a card built from the reply of the action, and the next refresh
     * could disagree with it.
     */
    @PostMapping("/briefing/schedule")
    public String schedule(@RequestParam String schedule) {
        client.changeSchedule(schedule);
        return "redirect:/";
    }

    @PostMapping("/briefing/collect")
    public String collect() {
        client.collectNow();
        return "redirect:/";
    }
}
