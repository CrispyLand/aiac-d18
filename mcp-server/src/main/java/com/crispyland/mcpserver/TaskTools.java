package com.crispyland.mcpserver;

import com.crispyland.mcpserver.google.TaskReader;
import com.google.api.services.tasks.Tasks;
import com.google.api.services.tasks.model.Task;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * What is still to do, from Google Tasks.
 * <p>
 * A sibling of {@link CalendarTools} and deliberately shaped like it: same authorized-service-in,
 * prose-out contract, same read-only guarantee inherited from the scopes rather than asserted
 * here. The two are separate classes because they are separate APIs — the Calendar UI displays
 * tasks beside events, which makes them look like one store, but {@code events.list} has never
 * returned a task. Merging them into one tool would mean one failing API silently truncating the
 * other's answer.
 * <p>
 * Nothing here knows about OAuth, and since the briefing job arrived, nothing here knows about the
 * Tasks API either — {@link TaskReader} owns the query, including the {@code dueMax} rule that used
 * to live in this file. The {@link Tasks} service arrives authorized one layer down, and the only
 * thing it can do is what {@code tasks.readonly} permits.
 */
@Service
public class TaskTools {

    private static final Logger log = LoggerFactory.getLogger(TaskTools.class);

    private final TaskReader reader;

    public TaskTools(TaskReader reader) {
        this.reader = reader;
    }

    @McpTool(name = "getTasks",
            description = "Get my incomplete Google Tasks from the default task list. With a date, "
                    + "returns only tasks due on or before that day; without one, returns all "
                    + "outstanding tasks. Note that tasks are separate from calendar events — use "
                    + "getSchedule for meetings and appointments. Read-only: this cannot create, "
                    + "change or delete anything.")
    public String getTasks(
            @McpToolParam(description = "Optional cut-off day, as an ISO date in yyyy-MM-dd form, "
                    + "e.g. '2026-03-17'. Omit to list every outstanding task.",
                    required = false) String date) throws IOException {

        LocalDate day = null;
        if (date != null && !date.isBlank()) {
            try {
                day = LocalDate.parse(date.strip());
            } catch (DateTimeParseException e) {
                // Returned rather than thrown. The model picked this string, so it is the one
                // party that can fix it, and it can only do that if the complaint names the format.
                return "'%s' is not a date I can read. Use ISO yyyy-MM-dd, for example 2026-03-17."
                        .formatted(date);
            }
        }

        if (!reader.hasTaskList()) {
            return "This Google account has no task lists.";
        }

        List<Task> items = (day == null) ? reader.outstanding() : reader.dueOnOrBefore(day);
        int found = items.size();
        log.info("getTasks({}) -> {} incomplete task(s)", (day == null) ? "all" : day, found);

        if (found == 0) {
            return (day == null)
                    ? "No outstanding tasks."
                    : "No outstanding tasks due on or before " + day + ".";
        }

        StringBuilder out = new StringBuilder(256)
                .append(found == 1 ? "1 outstanding task" : found + " outstanding tasks");
        if (day != null) {
            out.append(" due on or before ").append(day);
        }
        out.append(":\n");
        for (Task task : items) {
            out.append("- ").append(describe(task)).append('\n');
        }
        // Said once, at the end, because a date filter silently drops undated tasks and a list
        // that looks complete but is not is worse than a longer one.
        if (day != null) {
            out.append("(Tasks with no due date are not included when filtering by date.)");
        }
        return out.toString().stripTrailing();
    }

    /** One line per task: what it is, when it is due, and where it stands. */
    private String describe(Task task) {
        String title = (task.getTitle() == null || task.getTitle().isBlank())
                ? "(untitled)" : task.getTitle().strip();

        StringBuilder line = new StringBuilder(64).append(title);
        LocalDate due = TaskReader.dueDate(task);
        line.append(due == null ? " — no due date" : " — due " + due);

        // Always stated, even though this method only ever sees incomplete tasks. The status is
        // part of what was asked for, and a reader should not have to know how the query was
        // filtered to know what they are looking at.
        line.append(" [").append(completed(task) ? "completed" : "not completed").append(']');
        return line.toString();
    }

    private boolean completed(Task task) {
        return "completed".equals(task.getStatus());
    }
}
