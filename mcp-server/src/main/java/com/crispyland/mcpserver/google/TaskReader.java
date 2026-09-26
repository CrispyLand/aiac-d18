package com.crispyland.mcpserver.google;

import com.google.api.services.tasks.Tasks;
import com.google.api.services.tasks.model.Task;
import com.google.api.services.tasks.model.TaskList;
import com.google.api.services.tasks.model.TaskLists;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Outstanding tasks, fetched and nothing more — the sibling of {@link CalendarReader}, and split
 * out for the same reason.
 * <p>
 * This class exists mainly to be the <em>only</em> place that knows how {@code dueMax} behaves.
 * That rule cost a measured afternoon to find and reads as a mistake to anyone who has not been
 * bitten by it (see {@link #dueMaxFor}), so a second copy of it in the briefing job would have been
 * the bug that gets found months later when one of the two copies is corrected and the other is not.
 */
@Service
public class TaskReader {

    /** Enough for any sane to-do list, and a bound so a pathological one cannot return forever. */
    private static final int MAX_TASKS = 50;

    private final Tasks tasks;

    /** Memoized default list id; volatile because the scheduler thread reads it too. */
    private volatile String listId;

    public TaskReader(Tasks tasks) {
        this.tasks = tasks;
    }

    /** Every incomplete task in the default list, including undated ones. Never null. */
    public List<Task> outstanding() throws IOException {
        return list(null);
    }

    /**
     * Incomplete tasks due on or before the given day. Never null.
     * <p>
     * Undated tasks are excluded, because a date filter is a question about deadlines and a task
     * with no deadline has no answer to give. Callers that want everything ask {@link #outstanding}.
     */
    public List<Task> dueOnOrBefore(LocalDate day) throws IOException {
        return list(dueMaxFor(day));
    }

    /**
     * The {@code dueMax} bound for "due on or before this day", which is the day <em>after</em> it
     * at midnight UTC.
     * <p>
     * The Tasks API stores a due date with the time discarded and the instant pinned to midnight
     * UTC — it is a date wearing a timestamp's clothes. So the bound is built from the plain date,
     * NOT by converting a local day to UTC the way {@link CalendarReader} correctly does for
     * events. Doing that here would shift the cut-off by a day for anyone not on UTC.
     * <p>
     * And it is the day after, because {@code dueMax} gets the same treatment on read as
     * {@code due} does on write: the time half is thrown away and what remains is compared
     * strictly. An end-of-day bound of {@code day + T23:59:59.999Z} therefore collapses back to
     * that day's midnight and excludes the tasks due on it — measured, not assumed: with two tasks
     * due 2026-09-24, a dueMax of 2026-09-24T23:59:59.999Z returned none and 2026-09-25T23:59:59.999Z
     * returned both. Half-open at midnight is also what the comparison actually is, so it says what
     * it means.
     */
    static String dueMaxFor(LocalDate day) {
        return day.plusDays(1) + "T00:00:00.000Z";
    }

    /** Whether a task's due date is strictly before the given day. Null due dates are not overdue. */
    public static boolean isOverdue(Task task, LocalDate today) {
        LocalDate due = dueDate(task);
        return due != null && due.isBefore(today);
    }

    /**
     * A task's due date as a plain date, or null if it has none.
     * <p>
     * The field arrives as an RFC 3339 string whose time half is meaningless — Google discards it
     * on write — so the date is taken by truncating at the {@code T} rather than by parsing an
     * instant and converting it to a zone. Parsing would attach a midnight-UTC deadline nobody set,
     * and then shift it.
     */
    public static LocalDate dueDate(Task task) {
        String due = task.getDue();
        if (due == null || due.isBlank()) {
            return null;
        }
        int t = due.indexOf('T');
        String date = (t < 0) ? due : due.substring(0, t);
        try {
            return LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            // Google's own field, in Google's own format — but a snapshot of the whole day should
            // not be lost to one unparseable string, so this reads as "no due date" instead.
            return null;
        }
    }

    private List<Task> list(String dueMax) throws IOException {
        String listId = defaultListId();
        if (listId == null) {
            return List.of();
        }

        Tasks.TasksOperations.List request = tasks.tasks().list(listId)
                // Outstanding work only. showHidden stays false with it: a hidden task is one
                // already completed in Google's own clients, so asking for both would contradict
                // the question being answered.
                .setShowCompleted(false)
                .setShowHidden(false)
                .setMaxResults(MAX_TASKS);
        if (dueMax != null) {
            request.setDueMax(dueMax);
        }

        List<Task> items = request.execute().getItems();
        return (items == null) ? List.of() : items;
    }

    /**
     * The id of the account's default task list, or null if the account has none.
     * <p>
     * {@code tasklists().list()} returns the default first. The undocumented {@code @default}
     * alias would save this call and does work today, but it is absent from the REST reference
     * and the discovery document, which makes it a dependency on behaviour nobody promised.
     * <p>
     * Remembered after the first success, because the id of an account's default list does not
     * change and there are now two callers asking on every invocation — the tool per question, the
     * briefing job per run. A null is deliberately <em>not</em> remembered: an account that had no
     * list when the server started may have one by lunchtime, and caching "no" would mean a restart
     * is required to notice.
     */
    private String defaultListId() throws IOException {
        String known = listId;
        if (known != null) {
            return known;
        }
        TaskLists lists = tasks.tasklists().list().setMaxResults(1).execute();
        List<TaskList> items = lists.getItems();
        listId = (items == null || items.isEmpty()) ? null : items.get(0).getId();
        return listId;
    }

    /** Whether this Google account has a task list at all — the one case the tool words differently. */
    public boolean hasTaskList() throws IOException {
        return defaultListId() != null;
    }
}
