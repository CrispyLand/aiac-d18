package com.crispyland.mcpserver.briefing;

import java.time.Instant;

/**
 * One attempt at collecting, recorded whether or not it worked.
 * <p>
 * This exists because the snapshot alone cannot tell you the job is alive. A card showing
 * yesterday's figures is indistinguishable from a card showing today's until you look at the
 * timestamp — and a card showing yesterday's figures without mentioning that this morning's run
 * threw a 403 is actively misleading. The run log is the honesty layer: it says when the job last
 * tried, not when it last succeeded.
 *
 * @param at             when the attempt started
 * @param ok             whether the figures were collected and stored. Independent of
 *                       {@code narrated} — a run that stored a day but could not reach Groq is a
 *                       successful collection with no sentence, and flattening the two into one
 *                       boolean would make a missing API key look like a broken calendar
 * @param narrated       whether a sentence was written on this run. False for failures, for runs
 *                       where nothing had changed, and for narration that itself failed
 * @param detail         what happened, in a few words: the day's figures, "unchanged", or the
 *                       reason. Shown on the card, so it is written for a person
 * @param durationMillis how long it took, which is the only warning you get that the Google calls
 *                       are degrading before they start failing outright
 */
public record BriefingRun(
        Instant at,
        boolean ok,
        boolean narrated,
        String detail,
        long durationMillis) {

    public BriefingRun {
        detail = (detail == null) ? "" : detail.strip();
    }

    public static BriefingRun succeeded(Instant at, boolean narrated, String detail, long millis) {
        return new BriefingRun(at, true, narrated, detail, millis);
    }

    public static BriefingRun failed(Instant at, String detail, long millis) {
        return new BriefingRun(at, false, false, detail, millis);
    }
}
