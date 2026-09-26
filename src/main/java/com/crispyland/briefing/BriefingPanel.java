package com.crispyland.briefing;

import java.util.List;

/**
 * What the briefing card is showing, as of one page render.
 * <p>
 * A snapshot, for the same reason as {@code McpSnapshot}: the page is rendered once per request, and
 * a view that could re-dial the server halfway down the template would report two different states on
 * one screen.
 * <p>
 * Every time here arrives pre-formatted from the server rather than being rendered from an instant.
 * That is deliberate. The zone that decides where a calendar day starts belongs to the MCP server's
 * configuration, and on the VPS the agent's own JVM is set to UTC — formatting here would print the
 * 07:00 briefing as 23:00 the day before, on the one screen whose entire purpose is to show that the
 * 07:00 run happened.
 *
 * @param reachable whether the server answered. Kept separate from everything else because an
 *                  unreachable server and a job that has never run are different facts, and the card
 *                  must not let a network blip look like an idle scheduler
 * @param error     why it did not answer, or empty. Shown, not swallowed: "unreachable" with no reason
 *                  sends the reader to the logs of the wrong process half the time
 */
public record BriefingPanel(
        boolean reachable,
        String error,
        String schedule,
        String scheduleLabel,
        List<Option> options,
        String nextRunAt,
        String zone,
        Day latest,
        Run lastRun,
        List<Run> runs) {

    public BriefingPanel {
        error = (error == null) ? "" : error;
        schedule = (schedule == null) ? "off" : schedule;
        scheduleLabel = (scheduleLabel == null) ? "off" : scheduleLabel;
        options = (options == null) ? List.of() : List.copyOf(options);
        zone = (zone == null) ? "" : zone;
        latest = (latest == null) ? Day.NONE : latest;
        runs = (runs == null) ? List.of() : List.copyOf(runs);
    }

    /**
     * The panel for a server that did not answer.
     * <p>
     * The options list is empty, so the dropdown renders with nothing to choose — which is correct: a
     * schedule cannot be set on a server that is not there, and a dropdown offering to do it anyway
     * would be a button that silently does nothing.
     */
    public static BriefingPanel unreachable(String error) {
        return new BriefingPanel(false, error, null, null, List.of(), null, null, Day.NONE, null,
                List.of());
    }

    public boolean off() {
        return "off".equals(schedule);
    }

    /** Whether the card has a day to show at all. */
    public boolean hasBriefing() {
        return reachable && latest.present();
    }

    /**
     * The one state that must not look like a healthy run: figures collected, no sentence written.
     * The card says so in words rather than simply leaving a gap, because a gap reads as a quiet day.
     */
    public boolean missingNarrative() {
        return hasBriefing() && !latest.narrated();
    }

    /** Whether the most recent attempt failed — true even when an older snapshot is still shown. */
    public boolean lastRunFailed() {
        return reachable && lastRun != null && lastRun.present() && !lastRun.ok();
    }

    /** One entry of the schedule dropdown, as the server enumerated it. */
    public record Option(String id, String label) {
    }

    /** One collected day. */
    public record Day(
            boolean present,
            String date,
            String collectedAt,
            int events,
            int allDayEvents,
            String bookedTime,
            int tasksDue,
            int tasksOverdue,
            String figures,
            List<String> highlights,
            String narrative,
            boolean narrated) {

        public static final Day NONE =
                new Day(false, "", "", 0, 0, "0m", 0, 0, "", List.of(), "", false);

        public Day {
            date = (date == null) ? "" : date;
            collectedAt = (collectedAt == null) ? "" : collectedAt;
            bookedTime = (bookedTime == null) ? "0m" : bookedTime;
            figures = (figures == null) ? "" : figures;
            highlights = (highlights == null) ? List.of() : List.copyOf(highlights);
            narrative = (narrative == null) ? "" : narrative;
        }
    }

    /** One attempt, successful or not. */
    public record Run(
            boolean present,
            String at,
            boolean ok,
            boolean narrated,
            String detail,
            long durationMillis) {

        public Run {
            at = (at == null) ? "" : at;
            detail = (detail == null) ? "" : detail;
        }

        /** "unchanged — 3 events, …" is the common case, and the card dims it. */
        public boolean unchanged() {
            return detail.startsWith("unchanged");
        }
    }
}
