package com.crispyland.mcpserver.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.tasks.model.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of a day, with no Google and no stubs — which is the whole reason the sums live in
 * a static method instead of inside the collector.
 */
class BriefingsTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final Instant AT = Instant.parse("2026-09-26T07:00:00Z");

    private Briefing summarise(List<Event> events, List<Task> tasks) {
        return Briefings.summarise(DAY, events, tasks, SHANGHAI, AT);
    }

    /** Local wall-clock times on the day under test, which is how a calendar is actually read. */
    private Event event(String title, String startTime, String endTime) {
        return new Event()
                .setSummary(title)
                .setStart(at(startTime))
                .setEnd(at(endTime));
    }

    private EventDateTime at(String localTime) {
        Instant instant = DAY.atTime(java.time.LocalTime.parse(localTime)).atZone(SHANGHAI).toInstant();
        return new EventDateTime().setDateTime(new DateTime(instant.toEpochMilli()));
    }

    private Event allDayEvent(String title) {
        // The shape Google actually returns for a birthday: `date` set, `dateTime` absent.
        EventDateTime marker = new EventDateTime().setDate(new DateTime(true, AT.toEpochMilli(), 0));
        return new Event().setSummary(title).setStart(marker).setEnd(marker);
    }

    private Task task(String title, String due) {
        Task task = new Task().setTitle(title);
        return (due == null) ? task : task.setDue(due + "T00:00:00.000Z");
    }

    @Test
    void anEmptyDayIsZeroOfEverythingRatherThanAnAbsence() {
        Briefing briefing = summarise(List.of(), List.of());

        assertThat(briefing.isPresent()).isTrue();
        assertThat(briefing.events()).isZero();
        assertThat(briefing.bookedMinutes()).isZero();
        assertThat(briefing.highlights()).isEmpty();
        assertThat(briefing.narrative()).isEmpty();
        assertThat(briefing.narrated()).isFalse();
    }

    @Test
    void bookedMinutesIsTheSumOfTheTimedEvents() {
        Briefing briefing = summarise(
                List.of(event("standup", "09:00", "09:15"), event("review", "11:30", "12:30")),
                List.of());

        assertThat(briefing.bookedMinutes()).isEqualTo(75);
        assertThat(briefing.hoursAndMinutes()).isEqualTo("1h 15m");
        assertThat(briefing.events()).isEqualTo(2);
    }

    /**
     * An all-day event occupies the day without occupying any hours of it. Reported as an event,
     * counted separately, and worth nothing to the booked total — otherwise a birthday makes the
     * day look like twenty-four hours of meetings.
     */
    @Test
    void anAllDayEventIsCountedButBooksNoTime() {
        Briefing briefing = summarise(
                List.of(allDayEvent("Nur's birthday"), event("standup", "09:00", "09:15")),
                List.of());

        assertThat(briefing.events()).isEqualTo(2);
        assertThat(briefing.allDayEvents()).isEqualTo(1);
        assertThat(briefing.bookedMinutes()).isEqualTo(15);
        assertThat(briefing.highlights()).contains("all day — Nur's birthday");
    }

    /**
     * The day query returns anything overlapping the day, so a meeting that runs past midnight is
     * returned for both days. Counting its whole length on each would report more booked time than
     * the day physically holds — 25 hours in a 24-hour day is the kind of number nobody questions
     * until they do.
     */
    @Test
    void anEventRunningPastMidnightIsClippedToTheDay() {
        Instant start = DAY.atTime(23, 0).atZone(SHANGHAI).toInstant();
        Instant end = DAY.plusDays(1).atTime(1, 0).atZone(SHANGHAI).toInstant();
        Event overnight = new Event().setSummary("deploy window")
                .setStart(new EventDateTime().setDateTime(new DateTime(start.toEpochMilli())))
                .setEnd(new EventDateTime().setDateTime(new DateTime(end.toEpochMilli())));

        assertThat(summarise(List.of(overnight), List.of()).bookedMinutes()).isEqualTo(60);
    }

    @Test
    void anEventThatStartedYesterdayIsClippedAtTheStartOfTheDayToo() {
        Instant start = DAY.minusDays(1).atTime(22, 0).atZone(SHANGHAI).toInstant();
        Instant end = DAY.atTime(2, 0).atZone(SHANGHAI).toInstant();
        Event overnight = new Event().setSummary("on call")
                .setStart(new EventDateTime().setDateTime(new DateTime(start.toEpochMilli())))
                .setEnd(new EventDateTime().setDateTime(new DateTime(end.toEpochMilli())));

        assertThat(summarise(List.of(overnight), List.of()).bookedMinutes()).isEqualTo(120);
    }

    /** Due today is not late. The boundary is the whole point of the field. */
    @Test
    void onlyTasksDueBeforeTodayCountAsOverdue() {
        Briefing briefing = summarise(List.of(), List.of(
                task("due today", DAY.toString()),
                task("late", DAY.minusDays(1).toString()),
                task("later still", DAY.minusDays(4).toString()),
                task("no deadline", null)));

        assertThat(briefing.tasksDue()).isEqualTo(4);
        assertThat(briefing.tasksOverdue()).isEqualTo(2);
        assertThat(briefing.highlights())
                .contains("overdue: late (due 2026-09-25)", "overdue: later still (due 2026-09-22)");
    }

    /**
     * The highlights are pasted into the narrator's prompt, so the cap is a cost control as well as
     * a display one — and it has to say that it truncated, because a list that stops early reads as
     * a quieter day than it was.
     */
    @Test
    void aVeryFullDayIsTruncatedAndSaysSo() {
        List<Event> many = new java.util.ArrayList<>();
        for (int hour = 8; hour < 20; hour++) {
            many.add(event("meeting " + hour, "%02d:00".formatted(hour), "%02d:30".formatted(hour)));
        }

        Briefing briefing = summarise(many, List.of());

        assertThat(briefing.events()).isEqualTo(12);
        assertThat(briefing.highlights()).hasSize(Briefings.MAX_HIGHLIGHTS);
        assertThat(briefing.highlights().get(Briefings.MAX_HIGHLIGHTS - 1)).isEqualTo("… and 5 more");
    }

    @Test
    void anEventWithNoTitleIsNamedRatherThanBlank() {
        assertThat(summarise(List.of(event(null, "09:00", "09:30")), List.of()).highlights())
                .containsExactly("09:00–09:30 (no title)");
    }

    /**
     * The comparison the collector uses to decide whether a new sentence is worth paying for. It has
     * to ignore the collection time, because that differs on every single run — if it did not, every
     * run would look like a change and the every-minute setting would cost 1440 model calls a day.
     */
    @Test
    void twoRunsOfAnUnchangedDayHaveTheSameFiguresDespiteDifferentTimestamps() {
        List<Event> events = List.of(event("standup", "09:00", "09:15"));

        Briefing morning = Briefings.summarise(DAY, events, List.of(), SHANGHAI, AT);
        Briefing later = Briefings.summarise(DAY, events, List.of(), SHANGHAI, AT.plusSeconds(3600));

        assertThat(morning.sameFiguresAs(later)).isTrue();
        assertThat(morning.sameFiguresAs(later.withNarrative("anything"))).isTrue();
    }

    @Test
    void addingOneMeetingIsAChange() {
        Briefing before = summarise(List.of(event("standup", "09:00", "09:15")), List.of());
        Briefing after = summarise(
                List.of(event("standup", "09:00", "09:15"), event("review", "14:00", "15:00")),
                List.of());

        assertThat(before.sameFiguresAs(after)).isFalse();
    }

    @Test
    void aDayThatWasNeverCollectedIsNotTheSameAsAQuietDay() {
        assertThat(summarise(List.of(), List.of()).sameFiguresAs(Briefing.NONE)).isFalse();
        assertThat(Briefing.NONE.isPresent()).isFalse();
    }

    @Test
    void bookedTimeReadsAsHoursOnceItPassesOne() {
        assertThat(summarise(List.of(event("long", "09:00", "12:20")), List.of()).hoursAndMinutes())
                .isEqualTo("3h 20m");
        assertThat(summarise(List.of(event("round", "09:00", "11:00")), List.of()).hoursAndMinutes())
                .isEqualTo("2h");
        assertThat(summarise(List.of(), List.of()).hoursAndMinutes()).isEqualTo("0m");
    }

    @Test
    void theTrendSortsByDateWhateverOrderItIsGiven() {
        Briefing monday = new Briefing(DAY, AT, 2, 0, 120, 1, 0, List.of(), "");
        Briefing tuesday = new Briefing(DAY.plusDays(1), AT, 5, 0, 300, 3, 2, List.of(), "");

        Trend trend = Briefings.trend(List.of(tuesday, monday));

        assertThat(trend.from()).isEqualTo(DAY);
        assertThat(trend.to()).isEqualTo(DAY.plusDays(1));
        assertThat(trend.days()).isEqualTo(2);
        assertThat(trend.events()).isEqualTo(7);
        assertThat(trend.bookedMinutes()).isEqualTo(420);
        assertThat(trend.busiestDate()).isEqualTo(DAY.plusDays(1));
        assertThat(trend.averageBookedMinutesPerDay()).isEqualTo(210);
    }

    /**
     * Overdue is taken from the latest day rather than summed. The same forgotten task overdue all
     * week is one problem, not seven, and a total would read as seven.
     */
    @Test
    void theTrendReportsOverdueAsOfTheLastDayNotAsATotal() {
        Briefing monday = new Briefing(DAY, AT, 0, 0, 0, 1, 1, List.of(), "");
        Briefing tuesday = new Briefing(DAY.plusDays(1), AT, 0, 0, 0, 1, 1, List.of(), "");

        assertThat(Briefings.trend(List.of(monday, tuesday)).tasksOverdue()).isEqualTo(1);
    }

    @Test
    void aTrendOverNoStoredDaysIsEmptyRatherThanZeroed() {
        assertThat(Briefings.trend(List.of())).isEqualTo(Trend.EMPTY);
        assertThat(Briefings.trend(List.of(Briefing.NONE)).isEmpty()).isTrue();
    }
}
