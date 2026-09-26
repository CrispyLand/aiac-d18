package com.crispyland.mcpserver.briefing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The file the job writes to and the history the tools read from.
 * <p>
 * Modelled on the agent's {@code JsonFileBranchStore}: everything is held in memory, the whole file
 * is rewritten on every change, writes land via a temp file and an atomic move, and a file that
 * cannot be parsed is a warning and an empty start rather than a failed boot. The data is a few
 * hundred bytes a day, so streaming or a database would be effort spent making a small thing
 * complicated.
 * <p>
 * Three rules are enforced here rather than by the caller, because each is a property of the file:
 * <ul>
 *   <li><b>A date is a key, not a row.</b> Re-collecting today replaces today. A restart loop that
 *       appended would write five rows for one day and {@code getTrend} would report a week of
 *       phantom activity.</li>
 *   <li><b>The run log is bounded.</b> Set to every minute, this grows by 1440 entries a day; the
 *       card only ever shows a handful, so anything past {@code keepRuns} is dropped from the
 *       front.</li>
 *   <li><b>The schedule lives here too.</b> It is the one setting a person can change at runtime, so
 *       it has to survive the restart that follows — otherwise every deploy silently reverts the
 *       dropdown to whatever the YAML said.</li>
 * </ul>
 */
public class BriefingStore {

    private static final Logger log = LoggerFactory.getLogger(BriefingStore.class);

    private final ObjectMapper mapper;
    private final ObjectWriter writer;
    private final Path file;
    private final int keepRuns;

    /** Sorted by date, so "the latest day" and "the last seven" are both cheap and obvious. */
    private final TreeMap<LocalDate, Briefing> briefings = new TreeMap<>();

    /** Oldest first, the way a log reads. */
    private final List<BriefingRun> runs = new ArrayList<>();

    private BriefingSchedule schedule = BriefingSchedule.OFF;

    public BriefingStore(ObjectMapper mapper, Path file, int keepRuns, BriefingSchedule initial) {
        this.mapper = mapper;
        this.writer = mapper.writerWithDefaultPrettyPrinter();
        this.file = file;
        this.keepRuns = Math.max(1, keepRuns);
        this.schedule = initial;
        load();
    }

    public Path file() {
        return file.toAbsolutePath();
    }

    public synchronized BriefingSchedule schedule() {
        return schedule;
    }

    public synchronized void saveSchedule(BriefingSchedule schedule) {
        this.schedule = schedule;
        write();
    }

    /** The stored day, or {@link Briefing#NONE} if that day was never collected. */
    public synchronized Briefing briefing(LocalDate date) {
        return briefings.getOrDefault(date, Briefing.NONE);
    }

    /** The most recently dated snapshot, or {@link Briefing#NONE} when the file is empty. */
    public synchronized Briefing latest() {
        return briefings.isEmpty() ? Briefing.NONE : briefings.lastEntry().getValue();
    }

    /**
     * The snapshots from the last {@code days} days up to and including {@code upTo}, oldest first.
     * Days with no snapshot are simply absent — see {@link Trend#days()} for why they are not
     * invented as quiet days.
     */
    public synchronized List<Briefing> lastDays(LocalDate upTo, int days) {
        LocalDate from = upTo.minusDays(Math.max(1, days) - 1L);
        return new ArrayList<>(briefings.subMap(from, true, upTo, true).values());
    }

    public synchronized List<BriefingRun> runs() {
        return List.copyOf(runs);
    }

    /** The most recent attempt, successful or not, or null before anything has run. */
    public synchronized BriefingRun lastRun() {
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    /**
     * Store a collected day and the run that produced it, in one write.
     * <p>
     * One write rather than two because a snapshot without its run entry would be a day that
     * appeared from nowhere, and a run entry without its snapshot would claim a success the file
     * cannot show. They are the same fact.
     */
    public synchronized void save(Briefing briefing, BriefingRun run) {
        briefings.put(briefing.date(), briefing);
        runs.add(run);
        trimRuns();
        write();
    }

    /** Record an attempt that produced no snapshot — a failure, in practice. */
    public synchronized void recordRun(BriefingRun run) {
        runs.add(run);
        trimRuns();
        write();
    }

    private void trimRuns() {
        while (runs.size() > keepRuns) {
            runs.remove(0);
        }
    }

    private void write() {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temp, writer.writeValueAsString(toJson()));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist briefings to " + file.toAbsolutePath(), e);
        }
    }

    private ObjectNode toJson() {
        ObjectNode root = mapper.createObjectNode();
        // By id, not by name and not by ordinal. The ordinal would mean that inserting an option
        // between two existing ones silently changes what every stored file means.
        root.put("schedule", schedule.id());

        ObjectNode days = root.putObject("briefings");
        for (Map.Entry<LocalDate, Briefing> entry : briefings.entrySet()) {
            ObjectNode day = days.putObject(entry.getKey().toString());
            Briefing briefing = entry.getValue();
            day.put("collectedAt", String.valueOf(briefing.collectedAt()));
            day.put("events", briefing.events());
            day.put("allDayEvents", briefing.allDayEvents());
            day.put("bookedMinutes", briefing.bookedMinutes());
            day.put("tasksDue", briefing.tasksDue());
            day.put("tasksOverdue", briefing.tasksOverdue());
            ArrayNode highlights = day.putArray("highlights");
            briefing.highlights().forEach(highlights::add);
            if (!briefing.narrative().isEmpty()) {
                // Omitted when absent, so a file is not full of empty strings claiming a field that
                // was never filled. Read back as "" either way.
                day.put("narrative", briefing.narrative());
            }
        }

        ArrayNode log = root.putArray("runs");
        for (BriefingRun run : runs) {
            ObjectNode node = log.addObject();
            node.put("at", String.valueOf(run.at()));
            node.put("ok", run.ok());
            node.put("narrated", run.narrated());
            node.put("detail", run.detail());
            node.put("durationMillis", run.durationMillis());
        }
        return root;
    }

    /** A missing or corrupt file means "nothing has been collected yet" — never a failure to boot. */
    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));

            // An unrecognised id resolves to OFF rather than throwing, which is the only safe answer
            // during startup: a schedule is not worth refusing to boot over.
            schedule = BriefingSchedule.from(text(root.path("schedule")));

            JsonNode days = root.path("briefings");
            days.propertyNames().forEach(key -> {
                LocalDate date = date(key);
                if (date != null) {
                    briefings.put(date, briefing(date, days.path(key)));
                }
            });

            for (JsonNode node : root.path("runs")) {
                runs.add(new BriefingRun(instant(node.path("at")), node.path("ok").booleanValue(false),
                        node.path("narrated").booleanValue(false), text(node.path("detail")),
                        node.path("durationMillis").longValue(0)));
            }
            trimRuns();

            log.info("Restored {} briefing(s) and {} run(s) from {} (schedule: {})",
                    briefings.size(), runs.size(), file.toAbsolutePath(), schedule.id());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read briefings from {} ({}) — starting with no history.",
                    file.toAbsolutePath(), e.getMessage());
            briefings.clear();
            runs.clear();
        }
    }

    private static Briefing briefing(LocalDate date, JsonNode node) {
        List<String> highlights = new ArrayList<>();
        for (JsonNode line : node.path("highlights")) {
            if (line.isTextual()) {
                highlights.add(line.stringValue());
            }
        }
        // Every number is read with a default, and that is not belt-and-braces: in Jackson 3
        // `intValue()` on an absent field throws, so one field this version added would otherwise
        // cost the entire file — a day that was collected before the field existed is still a day.
        return new Briefing(date, instant(node.path("collectedAt")),
                node.path("events").intValue(0), node.path("allDayEvents").intValue(0),
                node.path("bookedMinutes").intValue(0), node.path("tasksDue").intValue(0),
                node.path("tasksOverdue").intValue(0), highlights, text(node.path("narrative")));
    }

    /** A key that is not a date belongs to a format this version does not know; skip it, keep the rest. */
    private static LocalDate date(String key) {
        try {
            return LocalDate.parse(key);
        } catch (DateTimeParseException e) {
            log.warn("Ignoring '{}' in the briefing file — not an ISO date.", key);
            return null;
        }
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.stringValue());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.stringValue() : "";
    }
}
