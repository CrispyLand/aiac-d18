package com.crispyland.mcpserver.briefing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One day, measured and described — the thing a scheduled run produces and the file keeps.
 * <p>
 * The numbers come first and the sentence second, and that order is the design. Everything except
 * {@link #narrative} is arithmetic over what Google returned, so two runs of the same day produce
 * the same figures and a diff of the file means something changed in the calendar rather than in the
 * weather of a language model. The narrative is the part a person actually reads; it is also the
 * part that can be absent, because it depends on a network call to a paid API that may be down, out
 * of quota, or unconfigured. A briefing with no sentence is degraded. A briefing with no figures
 * would be worthless, which is why they are computed before anything is asked of the model.
 *
 * @param date          the local day this describes
 * @param collectedAt   when the run that produced it happened, which is not the same as the day —
 *                      a day is re-collected many times as it fills up
 * @param events        how many events the day held, all-day ones included
 * @param allDayEvents  how many of those occupied the whole day rather than a slot. Counted
 *                      separately because they contribute nothing to {@code bookedMinutes} and a
 *                      reader comparing "4 events, 0 minutes booked" needs the explanation
 * @param bookedMinutes how much of the day was inside a timed event, clipped to the day's own
 *                      bounds so an event running past midnight is not counted twice
 * @param tasksDue      outstanding tasks due on or before this day
 * @param tasksOverdue  how many of those were already past their due date
 * @param highlights    the handful of lines worth naming: the day's events, then anything overdue.
 *                      Bounded, because this is both what the model is shown and what the card
 *                      renders, and an unbounded list would silently become a prompt of its own
 * @param narrative     one or two sentences about the above, or empty when narration was skipped or
 *                      failed. Empty is a state the UI must render differently, not a blank space
 */
public record Briefing(
        LocalDate date,
        Instant collectedAt,
        int events,
        int allDayEvents,
        int bookedMinutes,
        int tasksDue,
        int tasksOverdue,
        List<String> highlights,
        String narrative) {

    /** A day that has never been collected. Distinguishable from a quiet day by its null date. */
    public static final Briefing NONE =
            new Briefing(null, null, 0, 0, 0, 0, 0, List.of(), "");

    public Briefing {
        highlights = (highlights == null) ? List.of() : List.copyOf(highlights);
        narrative = (narrative == null) ? "" : narrative.strip();
    }

    public boolean isPresent() {
        return date != null;
    }

    public boolean narrated() {
        return !narrative.isEmpty();
    }

    /** The same day and figures, with a sentence attached. */
    public Briefing withNarrative(String narrative) {
        return new Briefing(date, collectedAt, events, allDayEvents, bookedMinutes,
                tasksDue, tasksOverdue, highlights, narrative);
    }

    /**
     * Whether the day looks exactly as it did when {@code other} was stored.
     * <p>
     * Deliberately ignores {@link #collectedAt} and {@link #narrative}: the first differs on every
     * single run and the second is derived from the rest. What is left is the question the collector
     * actually needs answered — "has anything happened since last time?" — and a `false` here is the
     * only thing that justifies paying for a new sentence. On a minute-by-minute schedule this is
     * what turns 1440 model calls a day into a handful.
     */
    public boolean sameFiguresAs(Briefing other) {
        return other != null
                && java.util.Objects.equals(date, other.date)
                && events == other.events
                && allDayEvents == other.allDayEvents
                && bookedMinutes == other.bookedMinutes
                && tasksDue == other.tasksDue
                && tasksOverdue == other.tasksOverdue
                && highlights.equals(other.highlights);
    }

    /** The figures as one line, for logs and for the prompt the narrator sends. */
    public String figures() {
        StringBuilder out = new StringBuilder(96)
                .append(events).append(events == 1 ? " event" : " events");
        if (allDayEvents > 0) {
            out.append(" (").append(allDayEvents).append(" all-day)");
        }
        out.append(", ").append(hoursAndMinutes()).append(" booked")
                .append(", ").append(tasksDue).append(tasksDue == 1 ? " task due" : " tasks due");
        if (tasksOverdue > 0) {
            out.append(", ").append(tasksOverdue).append(" overdue");
        }
        return out.toString();
    }

    /** Booked time as "3h 20m", or "0m" — minutes alone stop being legible above an hour. */
    public String hoursAndMinutes() {
        return Briefings.hoursAndMinutes(bookedMinutes);
    }
}
