package com.crispyland.mcpserver.briefing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The human controls: read the job's state, change the schedule, force a run.
 * <p>
 * Not MCP tools, and the reason is worth stating. Every tool this server exposes costs tokens on
 * every turn of every conversation, whether or not it is used, because its schema is in the prompt.
 * Job telemetry belongs on a page a person looks at occasionally, not in a model's context
 * permanently — and "change the schedule" is a setting, which is not something to hand a model that
 * has no stake in the electricity bill.
 * <p>
 * There is no authentication, and that is a deployment decision rather than an oversight: on the VPS
 * this process binds {@code 127.0.0.1}, so the only client that can reach it is the agent on the same
 * box. Exposing this port to a network would need auth added first; co-locating the two apps is what
 * makes that unnecessary.
 */
@RestController
@RequestMapping("/admin/briefing")
public class BriefingAdminController {

    /** As many as the card shows. The store keeps more; the page does not need them. */
    private static final int RUNS_SHOWN = 8;

    /**
     * Every instant is also sent pre-formatted in this zone, and that is not a convenience for the
     * template. The agent may be running on a machine set to UTC — it is, on the VPS — so a card that
     * formatted instants itself would print a 07:00 briefing as 23:00 the previous day. The zone that
     * decides where a calendar day starts is known here and nowhere else, so the rendering happens
     * here.
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BriefingStore store;
    private final BriefingScheduler scheduler;
    private final BriefingCollector collector;
    private final ZoneId zone;

    public BriefingAdminController(BriefingStore store, BriefingScheduler scheduler,
                                   BriefingCollector collector, Clock clock) {
        this.store = store;
        this.scheduler = scheduler;
        this.collector = collector;
        this.zone = clock.getZone();
    }

    /** Everything the card renders, in one call — the agent fetches this on every page render. */
    @GetMapping
    public Map<String, Object> state() {
        return snapshot();
    }

    /**
     * Change the schedule. An unrecognised id resolves to {@code OFF} rather than a 400: this is a
     * dropdown posting back one of its own values, so a bad value means a bug on the page, and
     * stopping the job is the safe direction to fail in.
     */
    @PostMapping("/schedule")
    public Map<String, Object> schedule(@RequestParam("schedule") String id) {
        scheduler.change(BriefingSchedule.from(id));
        return snapshot();
    }

    /**
     * Collect now, on this request thread.
     * <p>
     * Synchronous on purpose: the caller pressed a button and wants to see the result, and
     * {@code collect} is {@code synchronized} so a double-click queues rather than races. It is the
     * one path that spends a model call on demand, which is also why the schedule change deliberately
     * does not collect — otherwise every change of mind would cost a Google round trip and a Groq one.
     * <p>
     * {@code collectOnRequest} rather than {@code collect}: a human asking is also the only way to
     * fill in a sentence for a day that was already collected without one, since its figures will not
     * change again merely because the key was fixed.
     */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collect(
            @RequestParam(name = "date", required = false) String date) {
        LocalDate day = (date == null || date.isBlank()) ? collector.today() : LocalDate.parse(date);
        BriefingRun run = collector.collectOnRequest(day);
        Map<String, Object> body = snapshot();
        body.put("run", describe(run));
        // 200 either way: the request was handled, and the run's own `ok` says whether the collection
        // worked. A 500 here would mean the page could not tell "the button is broken" from "Google
        // said no", which are different problems with different fixes.
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schedule", store.schedule().id());
        body.put("scheduleLabel", store.schedule().label());
        body.put("options", options());
        body.put("nextRunAt", text(scheduler.nextRunAt()));
        body.put("nextRunAtLocal", local(scheduler.nextRunAt()));
        body.put("zone", zone.getId());
        body.put("today", collector.today().toString());
        body.put("file", store.file().toString());
        body.put("latest", describe(store.latest()));
        body.put("lastRun", describe(store.lastRun()));
        body.put("runs", recentRuns());
        return body;
    }

    /** The dropdown's contents come from the enum, so the page cannot offer an option that is not real. */
    private List<Map<String, String>> options() {
        List<Map<String, String>> options = new ArrayList<>();
        for (BriefingSchedule schedule : BriefingSchedule.values()) {
            options.add(Map.of("id", schedule.id(), "label", schedule.label()));
        }
        return options;
    }

    private List<Map<String, Object>> recentRuns() {
        List<BriefingRun> runs = store.runs();
        List<Map<String, Object>> recent = new ArrayList<>();
        // Newest first, which is the order a log is read on a page rather than in a file.
        for (int i = runs.size() - 1; i >= 0 && recent.size() < RUNS_SHOWN; i--) {
            recent.add(describe(runs.get(i)));
        }
        return recent;
    }

    /** A day that was never collected serialises as {@code present: false} rather than as zeroes. */
    private Map<String, Object> describe(Briefing briefing) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("present", briefing.isPresent());
        if (!briefing.isPresent()) {
            return node;
        }
        node.put("date", briefing.date().toString());
        node.put("collectedAt", text(briefing.collectedAt()));
        node.put("collectedAtLocal", local(briefing.collectedAt()));
        node.put("events", briefing.events());
        node.put("allDayEvents", briefing.allDayEvents());
        node.put("bookedMinutes", briefing.bookedMinutes());
        node.put("bookedTime", briefing.hoursAndMinutes());
        node.put("tasksDue", briefing.tasksDue());
        node.put("tasksOverdue", briefing.tasksOverdue());
        node.put("figures", briefing.figures());
        node.put("highlights", briefing.highlights());
        node.put("narrative", briefing.narrative());
        node.put("narrated", briefing.narrated());
        return node;
    }

    private Map<String, Object> describe(BriefingRun run) {
        if (run == null) {
            return Map.of("present", false);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("present", true);
        node.put("at", text(run.at()));
        node.put("atLocal", local(run.at()));
        node.put("ok", run.ok());
        node.put("narrated", run.narrated());
        node.put("detail", run.detail());
        node.put("durationMillis", run.durationMillis());
        return node;
    }

    /** Instants as ISO strings, with null meaning "never" — and never as an epoch number. */
    private String text(Instant at) {
        return (at == null) ? null : at.toString();
    }

    /** The same instant as a person in {@link #zone} would read it. */
    private String local(Instant at) {
        return (at == null) ? null : ZonedDateTime.ofInstant(at, zone).format(STAMP);
    }
}
