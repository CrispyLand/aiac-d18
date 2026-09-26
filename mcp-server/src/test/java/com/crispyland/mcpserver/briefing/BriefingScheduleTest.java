package com.crispyland.mcpserver.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

class BriefingScheduleTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime SEVEN = LocalTime.of(7, 0);

    @Test
    void anIdRoundTripsThroughTheFormAndTheFile() {
        for (BriefingSchedule schedule : BriefingSchedule.values()) {
            assertThat(BriefingSchedule.from(schedule.id())).isEqualTo(schedule);
        }
    }

    /**
     * This value is read during startup, from a file that a later version of this server may have
     * written and a person may have edited. Falling back beats throwing: a schedule nobody
     * recognises is not worth failing a boot over, and {@code OFF} is the safe direction — the job
     * does nothing until someone picks something, rather than running on a guess.
     */
    @Test
    void anythingUnrecognisedIsOffRatherThanAnException() {
        assertThat(BriefingSchedule.from("every-fortnight")).isEqualTo(BriefingSchedule.OFF);
        assertThat(BriefingSchedule.from("")).isEqualTo(BriefingSchedule.OFF);
        assertThat(BriefingSchedule.from("  ")).isEqualTo(BriefingSchedule.OFF);
        assertThat(BriefingSchedule.from(null)).isEqualTo(BriefingSchedule.OFF);
    }

    /** The enum name is accepted too, so a hand-edited file reading DAILY is not silently ignored. */
    @Test
    void theEnumNameIsAcceptedAsWellAsTheId() {
        assertThat(BriefingSchedule.from("DAILY")).isEqualTo(BriefingSchedule.DAILY);
        assertThat(BriefingSchedule.from("every-6-hours")).isEqualTo(BriefingSchedule.EVERY_6_HOURS);
    }

    @Test
    void offHasNoTriggerAtAll() {
        assertThat(BriefingSchedule.OFF.trigger(SEVEN, SHANGHAI)).isNull();
    }

    /**
     * The zone has to be explicit, and it is asserted through the instant the trigger actually picks
     * rather than through a getter — CronTrigger has no zone accessor, and the instant is the thing
     * that matters anyway. "07:00" resolved against the JVM's default zone means 07:00 in whatever
     * zone a rented server was imaged in, which is UTC on Hetzner, and therefore the wrong morning
     * for the person reading the briefing.
     */
    @Test
    void dailyFiresAtTheConfiguredHourInTheConfiguredZone() {
        Trigger trigger = BriefingSchedule.DAILY.trigger(LocalTime.of(7, 30), SHANGHAI);
        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getExpression()).isEqualTo("0 30 7 * * *");

        // Midnight UTC is already 08:00 in Shanghai, so 07:30 local has passed and the next run is
        // tomorrow morning there — 23:30 UTC. A trigger built in UTC would have said 07:30 UTC.
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        Instant next = trigger.nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC)));

        assertThat(next).isEqualTo(Instant.parse("2026-09-26T23:30:00Z"));
    }

    @Test
    void thePeriodicOptionsCarryTheIntervalTheirNamesClaim() {
        assertThat(period(BriefingSchedule.EVERY_MINUTE)).isEqualTo(Duration.ofMinutes(1));
        assertThat(period(BriefingSchedule.EVERY_5_MINUTES)).isEqualTo(Duration.ofMinutes(5));
        assertThat(period(BriefingSchedule.EVERY_15_MINUTES)).isEqualTo(Duration.ofMinutes(15));
        assertThat(period(BriefingSchedule.HOURLY)).isEqualTo(Duration.ofHours(1));
        assertThat(period(BriefingSchedule.EVERY_6_HOURS)).isEqualTo(Duration.ofHours(6));
    }

    /**
     * A full period of initial delay, so that choosing an option from the dropdown does not itself
     * collect. Asking for one now is what the button is for, and conflating the two would make every
     * change of mind cost a Google round trip and a model call.
     */
    @Test
    void changingTheScheduleDoesNotItselfFireARun() {
        PeriodicTrigger trigger =
                (PeriodicTrigger) BriefingSchedule.EVERY_15_MINUTES.trigger(SEVEN, SHANGHAI);

        assertThat(trigger.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(trigger.isFixedRate()).isTrue();
    }

    @Test
    void everyOptionHasSomethingToShowInTheDropdown() {
        for (BriefingSchedule schedule : BriefingSchedule.values()) {
            assertThat(schedule.label()).isNotBlank();
            assertThat(schedule.id()).isNotBlank();
        }
        assertThat(BriefingSchedule.OFF.isOff()).isTrue();
        assertThat(BriefingSchedule.DAILY.isOff()).isFalse();
    }

    private Duration period(BriefingSchedule schedule) {
        return ((PeriodicTrigger) schedule.trigger(SEVEN, SHANGHAI)).getPeriodDuration();
    }
}
