package com.crispyland.mcpserver.briefing;

import java.time.LocalDate;
import java.util.List;

/**
 * Several days at once — the answer that justifies keeping a file at all.
 * <p>
 * Every other number in this feature could be had by calling Google again. This one cannot: the
 * Calendar API will happily say what is on next Tuesday, but not that this week was forty minutes
 * busier than last, because nobody recorded last week. Aggregation over stored history is the only
 * thing here that is a consequence of persistence rather than a convenience on top of it.
 *
 * @param from          the earliest day included
 * @param to            the latest day included
 * @param days          how many days actually had a snapshot. Not {@code to - from}: a day the
 *                      server was switched off has no row, and pretending it was a quiet day would
 *                      turn downtime into a report of free time
 * @param events        total events across those days
 * @param bookedMinutes total booked time across those days
 * @param busiestDate   the day with the most booked minutes, or null when there is nothing to rank
 * @param tasksOverdue  overdue tasks as of the most recent snapshot — a current figure, not a sum,
 *                      because the same task overdue for five days would otherwise be counted five
 *                      times and read as five problems
 */
public record Trend(
        LocalDate from,
        LocalDate to,
        int days,
        int events,
        int bookedMinutes,
        LocalDate busiestDate,
        int tasksOverdue) {

    public static final Trend EMPTY = new Trend(null, null, 0, 0, 0, null, 0);

    public boolean isEmpty() {
        return days == 0;
    }

    public double averageEventsPerDay() {
        return (days == 0) ? 0 : (double) events / days;
    }

    public int averageBookedMinutesPerDay() {
        return (days == 0) ? 0 : bookedMinutes / days;
    }

    /** Built from snapshots already sorted by date; see {@link Briefings#trend(List)}. */
    static Trend of(List<Briefing> snapshots) {
        if (snapshots.isEmpty()) {
            return EMPTY;
        }
        int events = 0;
        int minutes = 0;
        LocalDate busiest = null;
        int busiestMinutes = -1;
        for (Briefing day : snapshots) {
            events += day.events();
            minutes += day.bookedMinutes();
            if (day.bookedMinutes() > busiestMinutes) {
                busiestMinutes = day.bookedMinutes();
                busiest = day.date();
            }
        }
        Briefing latest = snapshots.get(snapshots.size() - 1);
        return new Trend(snapshots.get(0).date(), latest.date(), snapshots.size(),
                events, minutes, busiest, latest.tasksOverdue());
    }
}
