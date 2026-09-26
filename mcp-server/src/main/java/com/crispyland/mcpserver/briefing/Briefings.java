package com.crispyland.mcpserver.briefing;

import com.crispyland.mcpserver.google.TaskReader;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.tasks.model.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The arithmetic, and nothing else.
 * <p>
 * No Google, no clock, no file, no Spring — the lists arrive already fetched and the instant arrives
 * as a parameter. That is what makes this the one part of the feature that can be tested by calling
 * a method: every rule worth arguing about lives here (what counts as booked, what an all-day event
 * contributes, when a task is overdue, how much of an event that crosses midnight belongs to which
 * day) and none of it needs a stub to exercise.
 * <p>
 * The alternative — computing these inside the collector, next to the API calls — would have meant
 * that checking whether a 23:00–01:00 meeting is double-counted required a Google account.
 */
public final class Briefings {

    /**
     * How many lines the narrator is shown. The full list is stored and displayed; only the prompt
     * is capped, so an unusually busy day cannot quietly buy itself a much larger model call.
     */
    static final int MAX_NARRATOR_HIGHLIGHTS = 8;

    /** Long enough for a real meeting title, short enough that one cannot dominate the prompt. */
    private static final int MAX_TITLE = 60;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Briefings() {
    }

    /**
     * Measure one day.
     *
     * @param date        the local day being described
     * @param events      that day's events, as {@code CalendarReader} returned them
     * @param tasks       outstanding tasks due on or before {@code date}
     * @param zone        the zone the day's boundaries are drawn in
     * @param collectedAt the moment of this measurement, passed in rather than read from a clock so
     *                    that the function stays a function
     */
    public static Briefing summarise(LocalDate date, List<Event> events, List<Task> tasks,
                                     ZoneId zone, Instant collectedAt) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();

        int allDay = 0;
        long minutes = 0;
        List<String> eventLines = new ArrayList<>();

        for (Event event : events) {
            Instant start = instant(event.getStart());
            Instant end = instant(event.getEnd());
            if (start == null || end == null) {
                // An all-day event carries `date` and no `dateTime`, so reading only the latter
                // returns null. It occupies the day without occupying any hours of it — counted,
                // named, and contributing nothing to the booked total.
                allDay++;
                eventLines.add("all day — " + title(event));
                continue;
            }
            // Clipped to the day. The query returns anything overlapping the day, so a meeting
            // running 23:00–01:00 appears on both days; counting its full two hours twice would
            // report more booked time than the day physically contains.
            Instant from = start.isBefore(dayStart) ? dayStart : start;
            Instant to = end.isAfter(dayEnd) ? dayEnd : end;
            if (to.isAfter(from)) {
                minutes += java.time.Duration.between(from, to).toMinutes();
            }
            eventLines.add(clock(start, zone) + "–" + clock(end, zone) + " " + title(event));
        }

        int overdue = 0;
        List<String> overdueLines = new ArrayList<>();
        for (Task task : tasks) {
            if (TaskReader.isOverdue(task, date)) {
                overdue++;
                overdueLines.add("overdue: " + taskTitle(task)
                        + " (due " + TaskReader.dueDate(task) + ")");
            }
        }

        // Events first, because they are what the day looks like; overdue tasks after, because they
        // are what is wrong with it. The full list is stored — the narrator caps its own view.
        List<String> highlights = new ArrayList<>(eventLines);
        highlights.addAll(overdueLines);

        return new Briefing(date, collectedAt, events.size(), allDay, (int) minutes,
                tasks.size(), overdue, highlights, "");
    }

    /**
     * Aggregate stored days. Input order is not trusted — the store is keyed by date, and a map's
     * iteration order is not a promise worth relying on for something a person will read as a
     * timeline.
     */
    public static Trend trend(List<Briefing> snapshots) {
        List<Briefing> sorted = new ArrayList<>(snapshots);
        sorted.removeIf(day -> !day.isPresent());
        sorted.sort(Comparator.comparing(Briefing::date));
        return Trend.of(sorted);
    }

    /**
     * Booked time as "3h 20m", or "0m". Here rather than on {@link Briefing} because a {@link Trend}
     * needs the same rendering for a total and an average, and three copies of "minutes stop being
     * legible above an hour" is two too many.
     */
    public static String hoursAndMinutes(int totalMinutes) {
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        if (hours == 0) {
            return minutes + "m";
        }
        return (minutes == 0) ? hours + "h" : hours + "h " + minutes + "m";
    }

    private static Instant instant(EventDateTime at) {
        if (at == null || at.getDateTime() == null) {
            return null;
        }
        return Instant.ofEpochMilli(at.getDateTime().getValue());
    }

    private static String clock(Instant at, ZoneId zone) {
        return ZonedDateTime.ofInstant(at, zone).format(TIME);
    }

    private static String title(Event event) {
        return shorten(event.getSummary(), "(no title)");
    }

    private static String taskTitle(Task task) {
        return shorten(task.getTitle(), "(untitled)");
    }

    private static String shorten(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String title = value.strip();
        return (title.length() <= MAX_TITLE) ? title : title.substring(0, MAX_TITLE - 1) + "…";
    }
}
