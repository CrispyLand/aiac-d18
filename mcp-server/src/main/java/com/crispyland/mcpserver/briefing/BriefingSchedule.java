package com.crispyland.mcpserver.briefing;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * How often the briefing is collected — a closed set, not a cron string typed into a text box.
 * <p>
 * Three things fall out of it being an enum. The dropdown's options are this list, so the UI cannot
 * offer something the server does not understand. An invalid value is unreachable, so there is no
 * "0 0 33 * * *" to reject at 07:00 tomorrow. And a stored value nobody recognises — a file written
 * by a later version, or edited by hand — resolves to {@link #OFF} rather than throwing on the way
 * up, which matters because this value is read during startup and a schedule is not worth failing a
 * boot over.
 * <p>
 * Both kinds of trigger come out of the same method, which is the point of Spring's {@link Trigger}
 * abstraction: {@code TaskScheduler.schedule(Runnable, Trigger)} takes either, so the scheduler has
 * one code path whether the answer is "at 07:00" or "every 15 minutes".
 */
public enum BriefingSchedule {

    OFF("off", "Off"),
    EVERY_MINUTE("every-minute", "Every minute"),
    EVERY_5_MINUTES("every-5-minutes", "Every 5 minutes"),
    EVERY_15_MINUTES("every-15-minutes", "Every 15 minutes"),
    HOURLY("hourly", "Hourly"),
    EVERY_6_HOURS("every-6-hours", "Every 6 hours"),
    DAILY("daily", "Daily");

    private final String id;
    private final String label;

    BriefingSchedule(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The stable form: what goes in the JSON file and in the form post. */
    public String id() {
        return id;
    }

    /** What the dropdown shows. */
    public String label() {
        return label;
    }

    public boolean isOff() {
        return this == OFF;
    }

    /**
     * The enum constant with this id, or {@link #OFF} for anything else — including null, blank, and
     * a name from a version that had more options than this one. Never throws.
     */
    public static BriefingSchedule from(String id) {
        if (id == null || id.isBlank()) {
            return OFF;
        }
        String wanted = id.strip();
        for (BriefingSchedule schedule : values()) {
            if (schedule.id.equalsIgnoreCase(wanted) || schedule.name().equalsIgnoreCase(wanted)) {
                return schedule;
            }
        }
        return OFF;
    }

    /** How long between runs, or null for {@link #DAILY} and {@link #OFF}, which are not periodic. */
    Duration period() {
        return switch (this) {
            case EVERY_MINUTE -> Duration.ofMinutes(1);
            case EVERY_5_MINUTES -> Duration.ofMinutes(5);
            case EVERY_15_MINUTES -> Duration.ofMinutes(15);
            case HOURLY -> Duration.ofHours(1);
            case EVERY_6_HOURS -> Duration.ofHours(6);
            case DAILY, OFF -> null;
        };
    }

    /**
     * The trigger to hand the scheduler, or null when this is {@link #OFF}.
     * <p>
     * {@code DAILY} is a {@link CronTrigger} pinned to an explicit zone, because "07:00" without one
     * means 07:00 wherever the JVM happens to think it is — and the whole point of the VPS is that it
     * is not where the user is. The periodic options are given an initial delay of one full period so
     * that changing the dropdown does not itself fire a collection; the "collect now" button is how
     * you ask for one immediately, and conflating the two would make every schedule change cost a
     * Google round trip and a model call.
     */
    public Trigger trigger(LocalTime dailyAt, ZoneId zone) {
        if (this == OFF) {
            return null;
        }
        if (this == DAILY) {
            return new CronTrigger("0 %d %d * * *".formatted(dailyAt.getMinute(), dailyAt.getHour()), zone);
        }
        Duration period = period();
        PeriodicTrigger trigger = new PeriodicTrigger(period);
        trigger.setFixedRate(true);
        trigger.setInitialDelay(period);
        return trigger;
    }
}
