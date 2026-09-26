package com.crispyland.mcpserver.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Restarting the server is simulated by building a second store over the same file — which is the
 * only way to test the thing the file is for. Every assertion here reads through a fresh instance
 * for that reason.
 */
class BriefingStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final Instant AT = Instant.parse("2026-09-26T07:00:00Z");

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void setUp() {
        // Nested, so that the store is also shown to create the directory it was pointed at.
        file = directory.resolve("data").resolve("briefings.json");
    }

    private BriefingStore store() {
        return store(BriefingSchedule.OFF);
    }

    private BriefingStore store(BriefingSchedule initial) {
        return new BriefingStore(JsonMapper.builder().build(), file, 3, initial);
    }

    private Briefing briefing(LocalDate date, int events, String narrative) {
        return new Briefing(date, AT, events, 0, events * 30, 1, 0,
                List.of("09:00–09:30 standup"), narrative);
    }

    private BriefingRun run(String detail) {
        return BriefingRun.succeeded(AT, true, detail, 12);
    }

    @Test
    void aCollectedDayAndItsSentenceBothSurviveARestart() {
        store().save(briefing(DAY, 2, "A two-meeting morning, then nothing."), run("collected"));

        Briefing restored = store().briefing(DAY);
        assertThat(restored.isPresent()).isTrue();
        assertThat(restored.collectedAt()).isEqualTo(AT);
        assertThat(restored.events()).isEqualTo(2);
        assertThat(restored.bookedMinutes()).isEqualTo(60);
        assertThat(restored.highlights()).containsExactly("09:00–09:30 standup");
        assertThat(restored.narrative()).isEqualTo("A two-meeting morning, then nothing.");
        assertThat(restored.narrated()).isTrue();
    }

    /**
     * The rule that makes the trend readable. A restart loop that appended would write five rows for
     * one day, and {@code getTrend} would report a week of activity that never happened.
     */
    @Test
    void collectingTheSameDayTwiceReplacesItRatherThanAppending() {
        store().save(briefing(DAY, 2, "first"), run("collected"));
        store().save(briefing(DAY, 5, "second"), run("collected"));

        BriefingStore restarted = store();
        assertThat(restarted.briefing(DAY).events()).isEqualTo(5);
        assertThat(restarted.briefing(DAY).narrative()).isEqualTo("second");
        assertThat(restarted.lastDays(DAY, 7)).hasSize(1);
    }

    @Test
    void theLatestDayIsTheLatestByDateAndNotByWriteOrder() {
        BriefingStore store = store();
        store.save(briefing(DAY, 1, "today"), run("collected"));
        store.save(briefing(DAY.minusDays(3), 9, "backfilled"), run("collected"));

        assertThat(store().latest().date()).isEqualTo(DAY);
    }

    @Test
    void theTrendWindowIsInclusiveAtBothEndsAndSkipsWhatWasNeverCollected() {
        BriefingStore store = store();
        store.save(briefing(DAY.minusDays(6), 1, ""), run("collected"));
        store.save(briefing(DAY.minusDays(9), 1, ""), run("collected"));
        store.save(briefing(DAY, 1, ""), run("collected"));

        assertThat(store().lastDays(DAY, 7)).extracting(Briefing::date)
                .containsExactly(DAY.minusDays(6), DAY);
    }

    /**
     * Set to every minute this grows by 1440 entries a day and the card shows a handful, so the log
     * is dropped from the front — and the bound has to hold across a restart too, or a long-running
     * server would keep a file the next boot then keeps forever.
     */
    @Test
    void theRunLogIsBoundedAndKeepsTheNewestEntries() {
        BriefingStore store = store();
        for (int i = 1; i <= 6; i++) {
            store.recordRun(BriefingRun.succeeded(AT.plusSeconds(i * 60L), true, "run " + i, 10));
        }

        assertThat(store().runs()).extracting(BriefingRun::detail)
                .containsExactly("run 4", "run 5", "run 6");
        assertThat(store().lastRun().detail()).isEqualTo("run 6");
    }

    @Test
    void aFailedAttemptIsRecordedWithoutInventingASnapshotForIt() {
        store().recordRun(BriefingRun.failed(AT, "calendar unreachable", 40));

        BriefingStore restarted = store();
        assertThat(restarted.lastRun().ok()).isFalse();
        assertThat(restarted.lastRun().detail()).isEqualTo("calendar unreachable");
        assertThat(restarted.lastRun().durationMillis()).isEqualTo(40);
        assertThat(restarted.latest().isPresent()).isFalse();
    }

    /**
     * The one setting a person changes at runtime, so it has to outlive the restart that follows —
     * otherwise every deploy silently reverts the dropdown to whatever the YAML said.
     */
    @Test
    void theChosenScheduleOutlivesTheRestartAndBeatsTheConfiguredDefault() {
        store(BriefingSchedule.OFF).saveSchedule(BriefingSchedule.EVERY_15_MINUTES);

        assertThat(store(BriefingSchedule.DAILY).schedule()).isEqualTo(BriefingSchedule.EVERY_15_MINUTES);
    }

    @Test
    void withNoFileTheConfiguredScheduleStandsAndThereIsNoHistory() {
        BriefingStore store = store(BriefingSchedule.HOURLY);

        assertThat(store.schedule()).isEqualTo(BriefingSchedule.HOURLY);
        assertThat(store.latest()).isEqualTo(Briefing.NONE);
        assertThat(store.runs()).isEmpty();
        assertThat(store.lastRun()).isNull();
    }

    /** A file that cannot be parsed costs the history, never the boot. */
    @Test
    void aCorruptFileStartsEmptyRatherThanFailingToStart() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");

        BriefingStore store = store(BriefingSchedule.DAILY);

        assertThat(store.latest()).isEqualTo(Briefing.NONE);
        assertThat(store.schedule()).isEqualTo(BriefingSchedule.DAILY);
        assertThatCode(() -> store.save(briefing(DAY, 1, "after"), run("collected")))
                .doesNotThrowAnyException();
        assertThat(store().briefing(DAY).narrative()).isEqualTo("after");
    }

    /**
     * A file written before the narrative existed — or by a later version that renamed it. Missing
     * fields read as zero and "", so an old file loses the sentence it never had rather than the day
     * it recorded.
     */
    @Test
    void aFileFromBeforeTheNarrativeExistedStillLoadsTheFiguresItHas() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schedule" : "daily",
                  "briefings" : {
                    "2026-09-20" : {
                      "collectedAt" : "2026-09-20T07:00:00Z",
                      "events" : 3,
                      "bookedMinutes" : 90,
                      "tasksDue" : 2,
                      "tasksOverdue" : 1,
                      "highlights" : [ "09:00–10:30 workshop" ]
                    },
                    "not-a-date" : { "events" : 99 }
                  },
                  "runs" : [ { "at" : "2026-09-20T07:00:00Z", "ok" : true, "detail" : "collected" } ]
                }
                """);

        BriefingStore store = store();

        Briefing old = store.briefing(LocalDate.of(2026, 9, 20));
        assertThat(old.events()).isEqualTo(3);
        assertThat(old.bookedMinutes()).isEqualTo(90);
        assertThat(old.tasksOverdue()).isEqualTo(1);
        assertThat(old.allDayEvents()).isZero();
        assertThat(old.narrative()).isEmpty();
        assertThat(old.narrated()).isFalse();
        // A key in a shape this version does not know is skipped, and the rest of the file is kept.
        assertThat(store.lastDays(LocalDate.of(2026, 9, 20), 1)).hasSize(1);
        assertThat(store.lastRun().narrated()).isFalse();
    }

    /** No half-written file is ever left behind for the next boot to choke on. */
    @Test
    void theTemporaryFileDoesNotOutliveTheWrite() {
        store().save(briefing(DAY, 1, "done"), run("collected"));

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(file.resolveSibling(file.getFileName() + ".tmp"))).isFalse();
    }
}
