package com.crispyland.mcpserver;

import com.crispyland.mcpserver.google.CalendarReader;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * One tool, backed by a real API: what is on the calendar for a given day.
 * <p>
 * The stubs this replaces made the point that a separate process can be interrogated over MCP.
 * They could not make the next one, which is the one that matters: that the model can act on
 * information nobody put in its training data or in this repository. A stub weather reading is
 * indistinguishable from a hallucinated one. A real appointment is not.
 * <p>
 * Nothing here knows about OAuth, and since the briefing job arrived, nothing here knows about the
 * Calendar API either: {@link CalendarReader} owns the query and this class owns the wording. The
 * read-only guarantee still holds by construction — it comes from the scope the {@link Calendar}
 * was authorized with, one layer further down, which is what makes "this tool cannot change my
 * calendar" checkable by reading a short file rather than trusting a comment.
 * <p>
 * The result is prose, not a data structure. The consumer is a language model reading a string,
 * so a JSON array of event objects would be re-read as text anyway, at a worse token price and
 * with punctuation the model has to look past. What it needs is the day, legibly.
 */
@Service
public class CalendarTools {

    private static final Logger log = LoggerFactory.getLogger(CalendarTools.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarReader reader;
    private final ZoneId zone;

    public CalendarTools(CalendarReader reader) {
        this.reader = reader;
        this.zone = reader.zone();
    }

    @McpTool(name = "getSchedule",
            description = "Get my Google Calendar events for a given day. Returns each event's "
                    + "start time, end time, title and location. Read-only: this cannot create, "
                    + "change or delete anything.")
    public String getSchedule(
            @McpToolParam(description = "The day to look up, as an ISO date in yyyy-MM-dd form, "
                    + "e.g. '2026-03-17'", required = true) String date) throws IOException {

        LocalDate day;
        try {
            day = LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            // Returned rather than thrown. The model picked this string, so it is the one party
            // that can fix it, and it can only do that if the complaint names the format.
            return "'%s' is not a date I can read. Use ISO yyyy-MM-dd, for example 2026-03-17."
                    .formatted(date);
        }

        List<Event> events = reader.eventsOn(day);
        log.info("getSchedule({}) -> {} event(s) in {}", day, events.size(), zone);

        if (events.isEmpty()) {
            return "Nothing scheduled on " + day + ".";
        }

        StringBuilder out = new StringBuilder(256)
                .append(events.size() == 1 ? "1 event on " : events.size() + " events on ")
                .append(day).append(":\n");
        for (Event event : events) {
            out.append("- ").append(describe(event)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** One line per event: when, what, and where if the invitation said. */
    private String describe(Event event) {
        String title = (event.getSummary() == null || event.getSummary().isBlank())
                ? "(no title)" : event.getSummary().strip();

        StringBuilder line = new StringBuilder(64).append(when(event)).append(" — ").append(title);
        String location = event.getLocation();
        if (location != null && !location.isBlank()) {
            line.append(" @ ").append(location.strip());
        }
        return line.toString();
    }

    /**
     * The time range, or the words "all day".
     * <p>
     * An all-day event carries {@code date} and no {@code dateTime}, so reading only the latter
     * returns null and a birthday becomes a crash. Google models the two cases differently
     * because they are different: one occupies hours, the other occupies the day.
     */
    private String when(Event event) {
        String start = clockTime(event.getStart());
        String end = clockTime(event.getEnd());
        if (start == null) {
            return "all day";
        }
        return (end == null) ? start : start + "–" + end;
    }

    /** Local wall-clock time, or null when this end of the event is a whole-day marker. */
    private String clockTime(EventDateTime at) {
        if (at == null || at.getDateTime() == null) {
            return null;
        }
        return ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(at.getDateTime().getValue()), zone).format(TIME);
    }
}
