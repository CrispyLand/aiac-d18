package com.crispyland.mcpserver.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.mcpserver.google.CalendarReader;
import com.crispyland.mcpserver.google.GoogleProperties;
import com.crispyland.mcpserver.google.TaskReader;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.tasks.model.Task;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import tools.jackson.databind.json.JsonMapper;

/**
 * The swap, without waiting for a clock.
 * <p>
 * A recording {@link TaskScheduler} instead of a real one: the questions here are "was the previous
 * task cancelled, and was it cancelled gently" and "does the choice outlive the process", neither of
 * which is answered by sleeping. A test that scheduled something every minute and waited would be
 * slower and would assert less.
 */
class BriefingSchedulerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant NOON = Instant.parse("2026-09-26T04:00:00Z");

    @TempDir
    Path directory;

    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final List<Event> events = new ArrayList<>();
    private final AtomicInteger collections = new AtomicInteger();

    private BriefingStore store;

    @BeforeEach
    void setUp() {
        store = store();
    }

    private BriefingStore store() {
        return new BriefingStore(JsonMapper.builder().build(),
                directory.resolve("briefings.json"), 20, BriefingSchedule.OFF);
    }

    private BriefingScheduler scheduler(BriefingStore store) {
        CalendarReader calendar = new CalendarReader(null, new GoogleProperties(null, null, 0, SHANGHAI.getId())) {
            @Override
            public List<Event> eventsOn(LocalDate day) {
                collections.incrementAndGet();
                return List.copyOf(events);
            }
        };
        TaskReader tasks = new TaskReader(null) {
            @Override
            public List<Task> dueOnOrBefore(LocalDate day) {
                return List.of();
            }
        };
        Clock clock = Clock.fixed(NOON, SHANGHAI);
        BriefingCollector collector = new BriefingCollector(calendar, tasks, store,
                BriefingNarrator.NONE, TelegramNotifier.NONE, clock);
        return new BriefingScheduler(scheduler, collector, store, clock,
                new BriefingProperties(null, null, LocalTime.of(7, 0), 20, null));
    }

    @Test
    void offSchedulesNothingAndCollectsNothing() {
        scheduler(store).start();

        assertThat(scheduler.submitted).isZero();
        assertThat(collections).hasValue(0);
    }

    /** An empty card on a fresh boot is worse than a nearly-empty day that the next run replaces. */
    @Test
    void aFirstStartWithASchedulePicksUpTodayImmediately() {
        store.saveSchedule(BriefingSchedule.DAILY);

        scheduler(store).start();

        assertThat(scheduler.submitted).isEqualTo(1);
        assertThat(collections).hasValue(1);
        assertThat(store.briefing(LocalDate.of(2026, 9, 26)).isPresent()).isTrue();
    }

    /** A restart later the same day must not re-collect a day the file already has. */
    @Test
    void aRestartOnADayAlreadyCollectedDoesNotCollectAgain() {
        store.saveSchedule(BriefingSchedule.DAILY);
        scheduler(store).start();
        collections.set(0);

        scheduler(store()).start();

        assertThat(collections).hasValue(0);
    }

    /**
     * The point of the whole class. Also asserts {@code cancel(false)}: {@code true} would interrupt
     * a collection that might be part-way through writing the file, and changing a dropdown is not
     * worth a half-written snapshot.
     */
    @Test
    void changingTheScheduleCancelsThePreviousTaskGentlyAndSubmitsANewOne() {
        BriefingScheduler briefingScheduler = scheduler(store);
        briefingScheduler.start();

        briefingScheduler.change(BriefingSchedule.EVERY_15_MINUTES);
        briefingScheduler.change(BriefingSchedule.HOURLY);

        assertThat(scheduler.submitted).isEqualTo(2);
        assertThat(scheduler.futures.get(0).cancelled).isTrue();
        assertThat(scheduler.futures.get(0).interrupted).isFalse();
        assertThat(scheduler.futures.get(1).cancelled).isFalse();
        assertThat(briefingScheduler.schedule()).isEqualTo(BriefingSchedule.HOURLY);
    }

    @Test
    void theChosenScheduleIsWrittenDownSoARestartKeepsIt() {
        scheduler(store).change(BriefingSchedule.EVERY_6_HOURS);

        assertThat(store().schedule()).isEqualTo(BriefingSchedule.EVERY_6_HOURS);
    }

    @Test
    void turningItOffCancelsTheTaskAndLeavesNoNextRun() {
        BriefingScheduler briefingScheduler = scheduler(store);
        briefingScheduler.change(BriefingSchedule.HOURLY);
        assertThat(briefingScheduler.nextRunAt()).isEqualTo(NOON.plusSeconds(3600));

        briefingScheduler.change(BriefingSchedule.OFF);

        assertThat(briefingScheduler.nextRunAt()).isNull();
        assertThat(scheduler.futures.get(0).cancelled).isTrue();
    }

    /** Daily reports the configured hour in the configured zone, which is what the card shows. */
    @Test
    void theNextRunOfTheDailyOptionIsTomorrowMorningWhenTodaysHasPassed() {
        BriefingScheduler briefingScheduler = scheduler(store);

        briefingScheduler.change(BriefingSchedule.DAILY);

        // Fixed at noon Shanghai, so 07:00 has gone: the next one is 07:00 tomorrow there, 23:00 UTC.
        assertThat(briefingScheduler.nextRunAt()).isEqualTo(Instant.parse("2026-09-26T23:00:00Z"));
    }

    /** Records what was asked of it; runs nothing. */
    private static class RecordingScheduler implements TaskScheduler {

        private final List<RecordedFuture> futures = new ArrayList<>();
        private int submitted;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            submitted++;
            RecordedFuture future = new RecordedFuture();
            futures.add(future);
            return future;
        }

        // The rest of the interface is abstract, so it has to be here — and throwing is the right
        // body rather than a no-op. The job is required to submit through schedule(Runnable, Trigger)
        // and nothing else: a Trigger is what lets one code path serve both a cron and an interval.
        // Any of these firing would mean the reschedule path had quietly grown a second way in.

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            throw byTriggerOnly();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw byTriggerOnly();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw byTriggerOnly();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            throw byTriggerOnly();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw byTriggerOnly();
        }

        private UnsupportedOperationException byTriggerOnly() {
            return new UnsupportedOperationException("the briefing job schedules by Trigger only");
        }
    }

    private static class RecordedFuture implements ScheduledFuture<Object> {

        private boolean cancelled;
        private boolean interrupted;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            interrupted = mayInterruptIfRunning;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
