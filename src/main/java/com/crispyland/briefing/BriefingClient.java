package com.crispyland.briefing;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the MCP server's admin API, and never throws.
 * <p>
 * Fail-soft is the whole design, not a nicety. This is called from a {@code @ModelAttribute}, which
 * runs before <em>every</em> rendered view including the POST that answers a chat turn — so an
 * exception escaping here would turn "the briefing server is restarting" into "the chat page is
 * broken". Every failure becomes {@link BriefingPanel#unreachable}, and the card says so.
 * <p>
 * Not over MCP, deliberately: this is a page panel talking to a management endpoint, and routing it
 * through the model's tool protocol would put job telemetry into every prompt and hand the model a
 * config switch. The JSON is hand-mapped for the same reason the rest of this project hand-maps it —
 * the shape is small, and a binding failure on a field the server renamed would take the page with it.
 */
public class BriefingClient {

    private static final Logger log = LoggerFactory.getLogger(BriefingClient.class);

    /** Short read timeout. Used for the read that happens on every page render. */
    private final RestClient quick;

    /**
     * Long read timeout, used only for the two buttons.
     * <p>
     * Two clients rather than one compromise: a timeout generous enough for "collect now" — two Google
     * reads and a model call, on the server's request thread — would be minutes of a hung chat page if
     * the same client served the panel, and a timeout short enough for the panel would make the button
     * report a failure while the work it asked for was still running.
     */
    private final RestClient patient;

    private final ObjectMapper mapper;
    private final String baseUrl;

    public BriefingClient(RestClient quick, RestClient patient, ObjectMapper mapper, String baseUrl) {
        this.quick = quick;
        this.patient = patient;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The current state, or an unreachable panel. */
    public BriefingPanel panel() {
        return get("/admin/briefing");
    }

    /** Change the schedule and return the state that resulted from it. */
    public BriefingPanel changeSchedule(String scheduleId) {
        return post("/admin/briefing/schedule?schedule=" + scheduleId);
    }

    /**
     * Collect now. Slow on purpose — the server does the Google reads and the model call on the
     * request thread, which is what makes the button's result the thing the page then shows. The read
     * timeout on this client has to be generous enough to cover it; see {@code AgentConfiguration}.
     */
    public BriefingPanel collectNow() {
        return post("/admin/briefing/collect");
    }

    private BriefingPanel get(String path) {
        try {
            return parse(quick.get().uri(baseUrl + path).retrieve().body(String.class));
        } catch (Exception e) {
            return unreachable(path, e);
        }
    }

    private BriefingPanel post(String path) {
        try {
            return parse(patient.post().uri(baseUrl + path).retrieve().body(String.class));
        } catch (Exception e) {
            return unreachable(path, e);
        }
    }

    /**
     * Catching {@link Exception} rather than a list of client exceptions: connection refused, a 500,
     * a read timeout, a body that is not JSON and a field of the wrong type all have the same answer
     * here, and an unlisted one would break the page. There is no fallback worth attempting, only a
     * panel that admits it.
     */
    private BriefingPanel unreachable(String path, Exception e) {
        String message = (e.getMessage() == null) ? e.getClass().getSimpleName() : e.getMessage();
        // DEBUG, not WARN: with a @ModelAttribute this fires on every render, so a server that is
        // simply not running would otherwise fill the agent's log with a line per keystroke's worth
        // of page. The card is where this belongs, and the card says it.
        log.debug("Briefing server unreachable at {}{}: {}", baseUrl, path, message);
        return BriefingPanel.unreachable(message);
    }

    private BriefingPanel parse(String body) {
        JsonNode root = mapper.readTree(body);

        List<BriefingPanel.Option> options = new ArrayList<>();
        for (JsonNode option : root.path("options")) {
            options.add(new BriefingPanel.Option(
                    text(option, "id"), text(option, "label")));
        }

        List<BriefingPanel.Run> runs = new ArrayList<>();
        for (JsonNode node : root.path("runs")) {
            BriefingPanel.Run run = run(node);
            if (run != null) {
                runs.add(run);
            }
        }

        return new BriefingPanel(true, "", text(root, "schedule"), text(root, "scheduleLabel"),
                options, text(root, "nextRunAtLocal"), text(root, "zone"),
                day(root.path("latest")), run(root.path("lastRun")), runs);
    }

    private BriefingPanel.Day day(JsonNode node) {
        if (!node.path("present").booleanValue(false)) {
            return BriefingPanel.Day.NONE;
        }
        List<String> highlights = new ArrayList<>();
        for (JsonNode line : node.path("highlights")) {
            highlights.add(line.stringValue(""));
        }
        return new BriefingPanel.Day(true, text(node, "date"), text(node, "collectedAtLocal"),
                node.path("events").intValue(0), node.path("allDayEvents").intValue(0),
                text(node, "bookedTime"), node.path("tasksDue").intValue(0),
                node.path("tasksOverdue").intValue(0), text(node, "figures"), highlights,
                text(node, "narrative"), node.path("narrated").booleanValue(false));
    }

    private BriefingPanel.Run run(JsonNode node) {
        if (!node.path("present").booleanValue(false)) {
            return null;
        }
        return new BriefingPanel.Run(true, text(node, "atLocal"),
                node.path("ok").booleanValue(false), node.path("narrated").booleanValue(false),
                text(node, "detail"), node.path("durationMillis").longValue(0));
    }

    /** Every read defaulted, so a field the server stops sending costs that field and not the card. */
    private String text(JsonNode node, String field) {
        return node.path(field).stringValue("");
    }
}
