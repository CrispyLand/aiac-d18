package com.crispyland.mcpserver.briefing;

/**
 * Turns a measured day into a sentence or two.
 * <p>
 * An interface with one method, for one reason: the collector's job is to never lose a collection,
 * and proving that requires a narrator that fails on demand. A stub is two lines here; making the
 * real client fail to order would mean an HTTP fixture in a test about file writing.
 * <p>
 * The contract is narrow and the important half is the failure half: <b>an implementation returns
 * {@code ""} rather than throwing</b> when it cannot produce prose. A missing sentence is a degraded
 * run; it must never become a lost day. The collector guards against a thrown exception anyway,
 * because "must not throw" is not the same as "cannot".
 */
public interface BriefingNarrator {

    /** One or two sentences about {@code briefing}, or {@code ""} if none could be written. */
    String narrate(Briefing briefing);

    /** For a deployment with no key configured: honest about producing nothing, and free. */
    BriefingNarrator NONE = briefing -> "";
}
