package com.crispyland.mcpserver.google;

import java.nio.file.Path;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the OAuth files live and how the consent flow should behave.
 * <p>
 * Prefixed {@code google} rather than {@code google.calendar} because one credential now covers
 * two APIs. The file, the token cache and the browser redirect belong to the Google account, not
 * to any one service reading from it.
 * <p>
 * Both paths default to the repository root — one directory above this server, which is where
 * Google Cloud Console's download lands and where the agent project's {@code .gitignore} already
 * covers them. They are properties rather than constants because both defaults are relative, and
 * a relative path is only as good as the working directory the process happens to start in.
 * <p>
 * They are held as {@code String} and converted here rather than declared as {@link Path}.
 * Spring's built-in {@code String → Path} conversion treats the value as a resource path and
 * normalizes it, which rejects a leading {@code ..} outright — the container refuses to start
 * with "has been normalized to [null]". {@link Path#of} has no such opinion, and a path that
 * climbs out of the working directory is exactly what is wanted here.
 *
 * @param credentialsFile the OAuth client downloaded from Google Cloud Console. Read, never
 *                        written. This is the application's identity, not the user's
 * @param tokenDirectory  where the granted credential is cached so consent is asked for once
 *                        rather than every start. This is the user's, and it is the file that
 *                        actually grants access to the account
 * @param receiverPort    the loopback port the browser is redirected back to after consent.
 *                        {@code -1} lets the receiver pick a free one, which is what a desktop
 *                        OAuth client is allowed to do — Google accepts any localhost port for
 *                        this client type, so pinning one buys nothing and can collide
 * @param timeZone        the zone that decides where a calendar day starts and ends. Empty means
 *                        the JVM's default, which is right when the server runs on the machine
 *                        whose schedule is being read. It governs events only; a task's due date
 *                        carries no time of day to place in a zone
 */
@ConfigurationProperties(prefix = "google")
public record GoogleProperties(
        String credentialsFile,
        String tokenDirectory,
        int receiverPort,
        String timeZone) {

    public GoogleProperties {
        credentialsFile = blankTo(credentialsFile, "../credentials.json");
        tokenDirectory = blankTo(tokenDirectory, "../.google-tokens");
        receiverPort = (receiverPort == 0) ? -1 : receiverPort;
        timeZone = (timeZone == null) ? "" : timeZone.strip();
    }

    /** The OAuth client file, absolute — the only form worth naming in an error message. */
    public Path credentialsPath() {
        return Path.of(credentialsFile).toAbsolutePath().normalize();
    }

    /** The credential cache directory, absolute. */
    public Path tokenPath() {
        return Path.of(tokenDirectory).toAbsolutePath().normalize();
    }

    /**
     * The zone {@link #timeZone} names, resolved. Here rather than at each use site because there
     * are now two of them — the reader that fetches a day's events and the job that measures how
     * much of that day was booked — and two copies of "blank means the JVM's" is one copy too many
     * for a rule that decides which events belong to which date.
     */
    public ZoneId zone() {
        return timeZone.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.strip();
    }
}
