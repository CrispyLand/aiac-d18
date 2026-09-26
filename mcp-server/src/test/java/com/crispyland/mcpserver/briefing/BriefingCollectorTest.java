package com.crispyland.mcpserver.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.mcpserver.google.CalendarReader;
import com.crispyland.mcpserver.google.GoogleProperties;
import com.crispyland.mcpserver.google.TaskReader;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.tasks.model.Task;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three behaviours that protect the data, with a stub reader and a stub narrator.
 * <p>
 * Stubs by subclassing rather than by a mocking framework: both readers take their Google client in
 * a constructor that does nothing but assign it, so {@code null} is a perfectly good argument for a
 * test that overrides the only method which would have used it. No Google account, no HTTP, no
 * fixtures — and the readers stay the shape the application uses rather than being reshaped into
 * interfaces that exist only for tests.
 */
class BriefingCollectorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final Instant NOON = DAY.atTime(12, 0).atZone(SHANGHAI).toInstant();

    @TempDir
    Path directory;

    private BriefingStore store;
    private final List<Event> events = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();

    /** Counted, because "did not call the narrator" is the assertion, not a side effect of one. */
    private final AtomicInteger narrations = new AtomicInteger();

    private IOException calendarFailure;

    @BeforeEach
    void setUp() {
        store = new BriefingStore(JsonMapper.builder().build(),
                directory.resolve("briefings.json"), 20, BriefingSchedule.OFF);
        events.add(event("standup", LocalTime.of(9, 0), LocalTime.of(9, 15)));
    }

    private BriefingCollector collector(BriefingNarrator narrator) {
        CalendarReader calendar = new CalendarReader(null, properties()) {
            @Override
            public List<Event> eventsOn(LocalDate day) throws IOException {
                if (calendarFailure != null) {
                    throw calendarFailure;
                }
                return List.copyOf(events);
            }
        };
        TaskReader taskReader = new TaskReader(null) {
            @Override
            public List<Task> dueOnOrBefore(LocalDate day) {
                return List.copyOf(tasks);
            }
        };
        return new BriefingCollector(calendar, taskReader, store, narrator,
                TelegramNotifier.NONE, Clock.fixed(NOON, ZoneOffset.UTC));
    }

    private BriefingNarrator counting(String sentence) {
        return briefing -> {
            narrations.incrementAndGet();
            return sentence;
        };
    }

    private GoogleProperties properties() {
        return new GoogleProperties(null, null, 0, SHANGHAI.getId());
    }

    private Event event(String title, LocalTime from, LocalTime to) {
        return new Event().setSummary(title)
                .setStart(at(from))
                .setEnd(at(to));
    }

    private EventDateTime at(LocalTime time) {
        return new EventDateTime().setDateTime(
                new DateTime(DAY.atTime(time).atZone(SHANGHAI).toInstant().toEpochMilli()));
    }

    /** The clock is UTC and the zone is not, so a naive "today" would be a day out at noon Shanghai. */
    @Test
    void todayIsTheLocalDayInTheCalendarsZone() {
        assertThat(collector(counting("x")).today()).isEqualTo(DAY);
    }

    @Test
    void aCollectedDayIsStoredWithItsSentenceAndLoggedAsNarrated() {
        BriefingRun run = collector(counting("A quiet morning with one standup.")).collect(DAY);

        assertThat(run.ok()).isTrue();
        assertThat(run.narrated()).isTrue();
        assertThat(run.detail()).contains("1 event", "15m booked");
        assertThat(store.briefing(DAY).narrative()).isEqualTo("A quiet morning with one standup.");
        assertThat(store.briefing(DAY).bookedMinutes()).isEqualTo(15);
    }

    /**
     * The property the whole ordering exists for: prose is optional, figures are not. A narrator that
     * throws must cost the sentence and nothing else.
     */
    @Test
    void aNarratorThatThrowsStillLeavesTheFiguresStored() {
        BriefingRun run = collector(briefing -> {
            narrations.incrementAndGet();
            throw new IllegalStateException("groq is having a day");
        }).collect(DAY);

        assertThat(narrations).hasValue(1);
        assertThat(run.ok()).isTrue();
        assertThat(run.narrated()).isFalse();
        assertThat(store.briefing(DAY).isPresent()).isTrue();
        assertThat(store.briefing(DAY).bookedMinutes()).isEqualTo(15);
        assertThat(store.briefing(DAY).narrative()).isEmpty();
    }

    /** No key configured looks like this, and it is a degraded run rather than a failed one. */
    @Test
    void aNarratorWithNothingToSayIsStillASuccessfulCollection()  {
        BriefingRun run = collector(BriefingNarrator.NONE).collect(DAY);

        assertThat(run.ok()).isTrue();
        assertThat(run.narrated()).isFalse();
        assertThat(store.briefing(DAY).events()).isEqualTo(1);
    }

    /**
     * The token-cost argument, asserted. At the every-minute setting this is the difference between a
     * handful of calls a day and 1440 of them.
     */
    @Test
    void anUnchangedDayDoesNotCallTheNarratorAndKeepsTheSentenceItHad() {
        BriefingCollector collector = collector(counting("A quiet morning with one standup."));
        collector.collect(DAY);

        BriefingRun second = collector.collect(DAY);

        assertThat(narrations).hasValue(1);
        assertThat(second.ok()).isTrue();
        assertThat(second.narrated()).isFalse();
        assertThat(second.detail()).startsWith("unchanged");
        assertThat(store.briefing(DAY).narrative()).isEqualTo("A quiet morning with one standup.");
    }

    /**
     * And the same is true when a human asks: pressing the button twice must not buy a second opinion
     * of the same seven numbers.
     */
    @Test
    void anUnchangedDayThatAlreadyHasASentenceIsNotRenarratedEvenOnRequest() {
        BriefingCollector collector = collector(counting("A quiet morning with one standup."));
        collector.collect(DAY);

        BriefingRun second = collector.collectOnRequest(DAY);

        assertThat(narrations).hasValue(1);
        assertThat(second.detail()).startsWith("unchanged");
        assertThat(store.briefing(DAY).narrative()).isEqualTo("A quiet morning with one standup.");
    }

    /**
     * The way back from a failed narration, and the reason the button is not simply {@code collect}.
     * A day whose first run had no key will never see its figures change just because the key was
     * fixed, so without this the day stays prose-less until midnight — which on a daily schedule is
     * the whole day.
     */
    @Test
    void anUnchangedDayWithNoSentenceIsNarratedWhenSomebodyAsksForIt() {
        collector(BriefingNarrator.NONE).collect(DAY);
        assertThat(store.briefing(DAY).narrated()).isFalse();

        BriefingRun retry = collector(counting("A quiet morning with one standup."))
                .collectOnRequest(DAY);

        assertThat(narrations).hasValue(1);
        assertThat(retry.narrated()).isTrue();
        // Not logged as "unchanged": something was paid for, and the run log is where that is visible.
        assertThat(retry.detail()).doesNotStartWith("unchanged");
        assertThat(store.briefing(DAY).narrative()).isEqualTo("A quiet morning with one standup.");
        assertThat(store.briefing(DAY).events()).isEqualTo(1);
    }

    /** A scheduled run does not take that way back — that is what keeps a wrong key from costing 1440. */
    @Test
    void aScheduledRunDoesNotRetryANarrationThatAlreadyFailedForThatDay() {
        collector(BriefingNarrator.NONE).collect(DAY);

        BriefingRun second = collector(counting("A quiet morning with one standup.")).collect(DAY);

        assertThat(narrations).hasValue(0);
        assertThat(second.detail()).startsWith("unchanged");
        assertThat(store.briefing(DAY).narrated()).isFalse();
    }

    @Test
    void aChangedDayNarratesAgainAndReplacesTheSentence() {
        AtomicInteger sentences = new AtomicInteger();
        BriefingCollector collector = collector(
                briefing -> "sentence " + sentences.incrementAndGet());
        collector.collect(DAY);

        events.add(event("review", LocalTime.of(14, 0), LocalTime.of(15, 0)));
        BriefingRun second = collector.collect(DAY);

        assertThat(second.narrated()).isTrue();
        assertThat(store.briefing(DAY).narrative()).isEqualTo("sentence 2");
        assertThat(store.briefing(DAY).bookedMinutes()).isEqualTo(75);
    }

    /**
     * A stale sentence on fresh figures is the one failure with no visible symptom — the card would
     * look healthy and describe a day that no longer exists.
     */
    @Test
    void aChangedDayWhoseNarrationFailsDropsTheOldSentenceRatherThanKeepingIt() {
        collector(counting("A quiet morning with one standup.")).collect(DAY);

        events.add(event("review", LocalTime.of(14, 0), LocalTime.of(15, 0)));
        BriefingRun second = collector(BriefingNarrator.NONE).collect(DAY);

        assertThat(second.narrated()).isFalse();
        assertThat(store.briefing(DAY).narrative()).isEmpty();
        assertThat(store.briefing(DAY).bookedMinutes()).isEqualTo(75);
    }

    /**
     * No prose is a degraded run; no data is a failed one. The distinction is the point of having two
     * booleans, and the card is built on it.
     */
    @Test
    void aGoogleFailureIsAFailedRunAndDoesNotTouchTheStoredDay() {
        BriefingCollector collector = collector(counting("A quiet morning with one standup."));
        collector.collect(DAY);
        calendarFailure = new IOException("401 Unauthorized");

        BriefingRun failed = collector.collect(DAY);

        assertThat(failed.ok()).isFalse();
        assertThat(failed.narrated()).isFalse();
        assertThat(failed.detail()).isEqualTo("401 Unauthorized");
        // The previous snapshot is left exactly as it was — including its timestamp, which is how the
        // card can say "collected at 07:00, last attempt failed at 08:00".
        assertThat(store.briefing(DAY).narrative()).isEqualTo("A quiet morning with one standup.");
        assertThat(store.briefing(DAY).collectedAt()).isEqualTo(NOON);
        assertThat(store.runs()).hasSize(2);
    }

    /**
     * An unchanged day is still re-stored, so the timestamp advances. Without that, a quiet day and a
     * job that died look identical on the card, and they are not the same news.
     */
    @Test
    void anUnchangedRunStillAdvancesTheCollectionTimestamp() {
        CalendarReader calendar = new CalendarReader(null, properties()) {
            @Override
            public List<Event> eventsOn(LocalDate day) {
                return List.copyOf(events);
            }
        };
        TaskReader taskReader = new TaskReader(null) {
            @Override
            public List<Task> dueOnOrBefore(LocalDate day) {
                return List.of();
            }
        };
        new BriefingCollector(calendar, taskReader, store, counting("first"),
                TelegramNotifier.NONE, Clock.fixed(NOON, ZoneOffset.UTC)).collect(DAY);
        new BriefingCollector(calendar, taskReader, store, counting("second"),
                TelegramNotifier.NONE, Clock.fixed(NOON.plusSeconds(600), ZoneOffset.UTC)).collect(DAY);

        assertThat(narrations).hasValue(1);
        assertThat(store.briefing(DAY).collectedAt()).isEqualTo(NOON.plusSeconds(600));
        assertThat(store.briefing(DAY).narrative()).isEqualTo("first");
    }
}
