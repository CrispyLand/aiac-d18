package com.crispyland.mcpserver.briefing;

import com.crispyland.mcpserver.google.CalendarReader;
import com.crispyland.mcpserver.google.TaskReader;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.tasks.model.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One run: read Google, measure the day, write a sentence if the day moved, store both, log what
 * happened.
 * <p>
 * The order is the design, and it is the same order as {@link Briefing}'s fields. Figures are
 * computed from data already in hand before the model is asked for anything, and they are written
 * whether or not it answers. Nothing the narrator can do — no key, a timeout, a 429, a thrown
 * {@code Error} — can turn a day that was successfully read into a day that was not recorded.
 * <p>
 * {@code synchronized} is the whole of the concurrency design, and it is enough because there are
 * exactly two callers: the scheduler, whose pool is deliberately one thread so two collections can
 * never overlap, and the "collect now" button, which arrives on a request thread. The lock is what
 * stops those two from interleaving a read-compare-write against the same date.
 */
@Service
public class BriefingCollector {

    private static final Logger log = LoggerFactory.getLogger(BriefingCollector.class);

    private final CalendarReader calendar;
    private final TaskReader tasks;
    private final BriefingStore store;
    private final BriefingNarrator narrator;
    private final Clock clock;
    private final ZoneId zone;

    public BriefingCollector(CalendarReader calendar, TaskReader tasks, BriefingStore store,
                             BriefingNarrator narrator, Clock clock) {
        this.calendar = calendar;
        this.tasks = tasks;
        this.store = store;
        this.narrator = narrator;
        this.clock = clock;
        // From the reader, not from the clock: the zone that decides which events belong to this day
        // has to be the same one the query drew its bounds in, or the edges of the day disagree.
        this.zone = calendar.zone();
    }

    /** The local day, in the zone the calendar is read in — which is what "today" has to mean here. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    public BriefingRun collectToday() {
        return collect(today());
    }

    /**
     * A scheduled run. Narrates only what changed — see {@link #collect(LocalDate, boolean)}.
     */
    public BriefingRun collect(LocalDate date) {
        return collect(date, false);
    }

    /**
     * A run somebody asked for, by pressing the button.
     * <p>
     * The only difference is that this one will fill a missing sentence on a day whose figures have
     * not moved. That exception exists because without it there is no way back at all: if the first
     * run of a day narrated against a wrong key, the figures will not obligingly change once the key
     * is fixed, and the day would stay prose-less until midnight. A person pressing a button is a
     * bounded retry — once per press — where a scheduled retry is 1440 a day.
     * <p>
     * It does not <em>re</em>narrate a day that already has a sentence. "Collect now" means bring the
     * card up to date, not buy another opinion of the same numbers.
     */
    public BriefingRun collectOnRequest(LocalDate date) {
        return collect(date, true);
    }

    /**
     * Collect one day. Returns the run it logged, so a caller pressing a button can be told what
     * happened without going back to the store for it.
     *
     * @param fillMissingNarrative whether an unchanged day with no sentence is worth a model call
     */
    public synchronized BriefingRun collect(LocalDate date, boolean fillMissingNarrative) {
        Instant at = clock.instant();
        long started = System.nanoTime();

        List<Event> events;
        List<Task> due;
        try {
            events = calendar.eventsOn(date);
            due = tasks.dueOnOrBefore(date);
        } catch (Exception e) {
            // No data is a failed run, and it is recorded as one. The alternative — leaving the run
            // log alone — would leave yesterday's snapshot on the card with nothing to say that this
            // morning's attempt threw a 403, which is worse than an empty card.
            BriefingRun run = BriefingRun.failed(at, reason(e), millisSince(started));
            log.warn("Briefing for {} failed: {}", date, run.detail());
            store.recordRun(run);
            return run;
        }

        Briefing fresh = Briefings.summarise(date, events, due, zone, at);
        Briefing stored = store.briefing(date);

        if (fresh.sameFiguresAs(stored) && !(fillMissingNarrative && !stored.narrated())) {
            // Nothing happened, so nothing is paid for. This is the entire reason the every-minute
            // option is demonstrable rather than expensive: the first run of a quiet hour buys a
            // sentence and the next fifty-nine cost nothing but two Google reads.
            //
            // The cost, stated plainly: if narration failed on the run that first recorded this day,
            // a *scheduled* run keeps no sentence until something in it actually changes. That is
            // deliberate — retrying on every unchanged run would mean an unattended job hammering a
            // paid API all day over a key that is wrong, and a wrong key is exactly when it would
            // happen. The button is the way back, which is what the condition above allows for.
            Briefing kept = fresh.withNarrative(stored.narrative());
            BriefingRun run = BriefingRun.succeeded(at, false, "unchanged — " + fresh.figures(),
                    millisSince(started));
            // Stored anyway, so `collectedAt` advances: that timestamp is how the card distinguishes
            // "the day is quiet" from "the job stopped running", and they must not look alike.
            store.save(kept, run);
            log.debug("Briefing for {} unchanged ({})", date, fresh.figures());
            return run;
        }

        String sentence = narrate(fresh);
        // On a changed day a failed narration stores no sentence rather than the previous one. The
        // old sentence described figures that no longer hold, so keeping it would be the one failure
        // mode with no visible symptom.
        BriefingRun run = BriefingRun.succeeded(at, !sentence.isEmpty(), fresh.figures(),
                millisSince(started));
        store.save(fresh.withNarrative(sentence), run);
        log.info("Briefing for {}: {}{}", date, fresh.figures(),
                sentence.isEmpty() ? " (not narrated)" : "");
        return run;
    }

    /**
     * The narrator is contracted to return {@code ""} rather than throw, so this catch is for the
     * case where it breaks that contract. Cheap insurance on the one thread the scheduler has: an
     * exception escaping here would end the run, and a repeatedly-cancelled task stops being
     * rescheduled at all.
     */
    private String narrate(Briefing briefing) {
        try {
            String sentence = narrator.narrate(briefing);
            return (sentence == null) ? "" : sentence.strip();
        } catch (Exception e) {
            log.warn("Narrator threw ({}: {}) — storing {} without a sentence.",
                    e.getClass().getSimpleName(), e.getMessage(), briefing.date());
            return "";
        }
    }

    /** Written for the card, so it names the failure rather than pasting a stack trace into the UI. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        String detail = (message == null || message.isBlank())
                ? e.getClass().getSimpleName() : message.strip();
        return (detail.length() <= 200) ? detail : detail.substring(0, 199) + "…";
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
