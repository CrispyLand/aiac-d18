package com.crispyland.mcpserver.google;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * A day's events, fetched and nothing more.
 * <p>
 * Split out of {@code CalendarTools} when a second consumer appeared. The tool turns events into
 * prose for a language model; the briefing job counts minutes. Neither can use the other's output,
 * but both need the same query — and the query is the part with the rule in it. Leaving the fetch
 * inside the tool would have meant the job either re-deriving the local-day bounds, or parsing
 * sentences back into times.
 * <p>
 * Read-only by construction: the {@link Calendar} arrives authorized with a readonly scope, and
 * nothing here asks it for anything else.
 */
@Service
public class CalendarReader {

    /**
     * The calendar being read. {@code primary} is Google's alias for the signed-in user's own
     * calendar, which is the one consent was given for.
     */
    private static final String CALENDAR_ID = "primary";

    /** Enough for any single day, and a bound so a pathological day cannot return unlimited rows. */
    private static final int MAX_EVENTS = 50;

    private final Calendar calendar;
    private final ZoneId zone;

    public CalendarReader(Calendar calendar, GoogleProperties properties) {
        this.calendar = calendar;
        this.zone = properties.zone();
    }

    /** The zone these events were placed in, for anything that has to read their clock times. */
    public ZoneId zone() {
        return zone;
    }

    /**
     * Everything on the given local day, in start order, recurrences expanded.
     * <p>
     * Never null — an empty list is the honest answer for a free day, and a caller that has to
     * null-check before counting will eventually forget to.
     */
    public List<Event> eventsOn(LocalDate day) throws IOException {
        // The full LOCAL day. Asking Google for a UTC day would quietly drop an evening event for
        // anyone east of Greenwich and invent one for anyone west, which is the kind of bug that
        // only shows up in someone else's timezone.
        ZonedDateTime from = day.atStartOfDay(zone);
        ZonedDateTime to = day.plusDays(1).atStartOfDay(zone);

        Events result = calendar.events().list(CALENDAR_ID)
                .setTimeMin(new DateTime(from.toInstant().toEpochMilli()))
                .setTimeMax(new DateTime(to.toInstant().toEpochMilli()))
                // Expands a recurring series into the occurrences that actually fall on this day.
                // Without it the API returns the recurrence rule instead, which answers a
                // different question than the one asked.
                .setSingleEvents(true)
                .setOrderBy("startTime")
                .setMaxResults(MAX_EVENTS)
                .execute();

        List<Event> events = result.getItems();
        return (events == null) ? List.of() : events;
    }
}
