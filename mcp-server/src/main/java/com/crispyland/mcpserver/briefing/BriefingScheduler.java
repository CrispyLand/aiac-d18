package com.crispyland.mcpserver.briefing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Service;

/**
 * Holds the one scheduled task, and swaps it when someone changes the dropdown.
 * <p>
 * This is the reason the job is not a {@code @Scheduled} method. That annotation's cron is read once,
 * while the bean is created, and there is no supported way to change it afterwards — so a runtime
 * dropdown would have meant either restarting the context or running every minute and returning early
 * most of the time. Holding a {@link ScheduledFuture} and re-submitting costs about forty lines and
 * gives an honest answer to "when does it next run?".
 * <p>
 * {@code @EnableScheduling} is still required, and not as decoration: Boot only contributes a
 * {@link TaskScheduler} bean when that annotation's processor is present. Its pool stays at the
 * default of one thread, which is load-bearing — one thread means two collections can never overlap,
 * so nothing has to lock the store against itself.
 */
@Service
public class BriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefingScheduler.class);

    private final TaskScheduler scheduler;
    private final BriefingCollector collector;
    private final BriefingStore store;
    private final Clock clock;
    private final LocalTime dailyAt;
    private final ZoneId zone;

    /** The live task, or null when the schedule is {@code OFF}. Guarded by {@code this}. */
    private ScheduledFuture<?> scheduled;

    /**
     * What the trigger said it would do next, remembered because a {@link Trigger} is not asked twice:
     * {@code nextExecution} advances a cron expression against a context, so calling it again to show
     * a person a time would be answering a different question than the scheduler asked.
     */
    private Instant nextRunAt;

    public BriefingScheduler(TaskScheduler scheduler, BriefingCollector collector,
                             BriefingStore store, Clock clock, BriefingProperties properties) {
        this.scheduler = scheduler;
        this.collector = collector;
        this.store = store;
        this.clock = clock;
        this.dailyAt = properties.dailyAt();
        this.zone = clock.getZone();
    }

    /**
     * Start the stored schedule, and collect once if today has never been collected.
     * <p>
     * On {@code ApplicationReadyEvent} rather than in the constructor: the first collection talks to
     * Google and then to Groq, and a bean that does that while the context is still being built turns
     * a network hiccup into a failure to start.
     * <p>
     * The catch-up is deliberately the blunt version — "no snapshot for today" rather than "past
     * today's trigger time". A 03:00 restart therefore collects a nearly-empty day, which the 07:00
     * run then replaces. The precise version buys nothing except an empty card on a fresh boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        apply(store.schedule());
        if (schedule().isOff()) {
            return;
        }
        if (!store.briefing(collector.today()).isPresent()) {
            log.info("No briefing for {} yet — collecting once at startup.", collector.today());
            // On this thread, not the scheduler's: if Google is unreachable the failure belongs in
            // the startup log where someone is looking, rather than in a run log they have to open.
            collector.collectToday();
        }
    }

    public synchronized BriefingSchedule schedule() {
        return store.schedule();
    }

    /** When the job next runs, or null when it is off. */
    public synchronized Instant nextRunAt() {
        return (scheduled == null) ? null : nextRunAt;
    }

    /** Change the schedule and persist the choice, so it survives the next restart. */
    public synchronized void change(BriefingSchedule schedule) {
        store.saveSchedule(schedule);
        apply(schedule);
    }

    private void apply(BriefingSchedule schedule) {
        cancel();
        Trigger trigger = schedule.trigger(dailyAt, zone);
        if (trigger == null) {
            log.info("Briefing collection is off.");
            return;
        }
        scheduled = scheduler.schedule(this::run, trigger);
        nextRunAt = nextExecutionOf(trigger);
        log.info("Briefing collection set to {} (next run {}).", schedule.label(), nextRunAt);
    }

    private void cancel() {
        if (scheduled != null) {
            // false, never true: interrupting means interrupting a collection that may be part-way
            // through writing the file, and the point of changing a dropdown is not worth a
            // half-written snapshot.
            scheduled.cancel(false);
            scheduled = null;
        }
        nextRunAt = null;
    }

    /**
     * The scheduled body. Nothing is allowed to escape it: a task that throws is not rescheduled by
     * {@code ScheduledExecutorService}, so one unhandled exception would silently end the job for the
     * lifetime of the process — the failure mode with no symptom except a card that stops updating.
     */
    private void run() {
        try {
            collector.collectToday();
        } catch (Exception e) {
            log.error("Scheduled briefing threw, which should not happen — the job continues.", e);
        } finally {
            refreshNextRun();
        }
    }

    private synchronized void refreshNextRun() {
        Trigger trigger = store.schedule().trigger(dailyAt, zone);
        if (trigger != null && scheduled != null) {
            nextRunAt = nextExecutionOf(trigger);
        }
    }

    /**
     * Asked of a throwaway trigger against a fresh context, purely to display — never of the live
     * one. {@code nextExecution} advances a cron expression against the context it is given, so
     * asking the scheduler's own trigger would be participating in its bookkeeping rather than
     * reading it. For the periodic options a fresh context yields "now plus the interval", which is
     * exact right after a run and optimistic in between; the card says "next run", not "countdown".
     */
    private Instant nextExecutionOf(Trigger trigger) {
        return trigger.nextExecution(new SimpleTriggerContext(clock));
    }
}
