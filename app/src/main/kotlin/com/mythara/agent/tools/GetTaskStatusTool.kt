package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.agent.todo.AgentTodoStore
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `get_task_status` — single tool that lets the agent (and
 * therefore the user, by asking the agent) query the current
 * status of:
 *
 *   1. **Scheduled / cross-device tasks** managed by
 *      [TaskRepository] — anything created by `schedule_task`,
 *      `create_reminder`, or queued for cross-device execution
 *      (PENDING → CLAIMED → RUNNING → DONE/FAILED/MISSED/
 *      CANCELED). Returns id, title, status, scheduled time,
 *      finished time, and the result text or error reason.
 *
 *   2. **Internal todo queue** maintained by
 *      [AgentTodoStore] — items the agent is auto-continuing
 *      through (status pending / done / failed / skipped),
 *      with their source (UserIntent / MemoryPattern /
 *      SelfContinuation / External).
 *
 * Two query shapes:
 *   - `id: "<task-id>"` — return one specific scheduled task
 *     by its TaskRepository id.
 *   - `scope: "tasks" | "todo" | "all"` (default "all") —
 *     return a summary of recent items. The default returns
 *     both: pending + last-10 terminal entries from each
 *     source.
 *
 * Use cases:
 *   - User: "what tasks are running right now?" → agent calls
 *     get_task_status with scope="tasks" → answers from the
 *     RUNNING + CLAIMED rows.
 *   - User: "what's left on your todo list?" → agent calls
 *     get_task_status with scope="todo" → answers from the
 *     pending list.
 *   - User: "did the laundry reminder I set yesterday fire?" →
 *     agent calls list_scheduled_tasks (or get_task_status
 *     scope="tasks") to find the id, then this tool with
 *     id=... to confirm DONE status + when it fired.
 *
 * Read-only — no ConfirmationGate.
 */
@Singleton
class GetTaskStatusTool @Inject constructor(
    private val taskRepo: TaskRepository,
    private val todoStore: AgentTodoStore,
) : Tool {

    override val name: String = "get_task_status"
    override val description: String =
        "Get the current status of scheduled tasks (PENDING/CLAIMED/RUNNING/DONE/FAILED/" +
            "MISSED/CANCELED) and/or items in the agent's internal auto-continue todo queue. " +
            "Pass an `id` for one specific scheduled task, or `scope` ('tasks' | 'todo' | 'all') " +
            "for a summary."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "id",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional — TaskRepository id of one specific scheduled task. When set, " +
                                "scope is ignored and only that task's row is returned.",
                        )
                    },
                )
                put(
                    "scope",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("tasks"))
                                add(JsonPrimitive("todo"))
                                add(JsonPrimitive("all"))
                            },
                        )
                        put(
                            "description",
                            "What to return when no id is given. 'tasks' = scheduled/cross-device " +
                                "tasks; 'todo' = agent auto-continue todo items; 'all' (default) = both.",
                        )
                    },
                )
            },
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val scope = (args["scope"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
            .ifBlank { "all" }

        if (id.isNotEmpty()) {
            val row = taskRepo.dao.byId(id)
                ?: return ToolResult.fail(
                    """{"error":"not_found","detail":"No scheduled task with id '$id'."}""",
                )
            return ToolResult.ok(
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("kind", JsonPrimitive("task"))
                    put("task", taskRowToJson(row))
                }.toString(),
            )
        }

        val showTasks = scope == "tasks" || scope == "all"
        val showTodo = scope == "todo" || scope == "all"

        return ToolResult.ok(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("scope", JsonPrimitive(scope))
                if (showTasks) put("tasks", buildTasksSection())
                if (showTodo) put("todo", buildTodoSection())
            }.toString(),
        )
    }

    private suspend fun buildTasksSection(): JsonObject {
        val all = runCatching { taskRepo.dao.listRecent(limit = 100) }.getOrDefault(emptyList())
        val active = all.filter { it.status in ACTIVE_STATUSES }
            .sortedBy { it.scheduledForMs ?: it.createdMs }
        val terminal = all.filter { it.status !in ACTIVE_STATUSES }
            .sortedByDescending { it.completedMs ?: it.createdMs }
            .take(10)
        return buildJsonObject {
            put("active_count", JsonPrimitive(active.size))
            put("active", JsonArray(active.map { taskRowToJson(it) }))
            put("recent_terminal", JsonArray(terminal.map { taskRowToJson(it) }))
        }
    }

    private suspend fun buildTodoSection(): JsonObject {
        val all = runCatching { todoStore.all() }.getOrDefault(emptyList())
        val pending = all.filter { it.status == AgentTodoStore.Status.Pending }
        val terminal = all.filter { it.status != AgentTodoStore.Status.Pending }
            .sortedByDescending { it.createdAtMs }
            .take(10)
        return buildJsonObject {
            put("pending_count", JsonPrimitive(pending.size))
            put("pending", JsonArray(pending.map { todoItemToJson(it) }))
            put("recent_terminal", JsonArray(terminal.map { todoItemToJson(it) }))
        }
    }

    private fun taskRowToJson(row: com.mythara.tasks.TaskEntity): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(row.id))
        put("title", JsonPrimitive(row.title))
        put("status", JsonPrimitive(row.status))
        put("created", JsonPrimitive(HUMAN_FMT.format(Date(row.createdMs))))
        row.scheduledForMs?.let { put("scheduled_for", JsonPrimitive(HUMAN_FMT.format(Date(it)))) }
        row.claimedMs?.let { put("claimed", JsonPrimitive(HUMAN_FMT.format(Date(it)))) }
        row.completedMs?.let { put("completed", JsonPrimitive(HUMAN_FMT.format(Date(it)))) }
        row.claimedByDeviceId?.let { put("claimed_by", JsonPrimitive(it)) }
        row.targetDeviceId?.let { put("target_device", JsonPrimitive(it)) }
        row.recurrence?.let { put("recurrence", JsonPrimitive(it)) }
        // result_text doubles as error reason when status == FAILED.
        row.resultText?.takeIf { it.isNotBlank() }?.let {
            put(
                if (row.status == TaskStatus.FAILED.name) "error" else "result",
                JsonPrimitive(it.take(400)),
            )
        }
        // 80-char body preview so the agent can recognise the task
        // without dumping the full prompt into the response.
        put("preview", JsonPrimitive(row.body.take(80)))
    }

    private fun todoItemToJson(item: AgentTodoStore.Item): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(item.id))
        put("text", JsonPrimitive(item.text.take(200)))
        put("source", JsonPrimitive(item.source.name))
        put("status", JsonPrimitive(item.status.name))
        put("created", JsonPrimitive(HUMAN_FMT.format(Date(item.createdAtMs))))
    }

    companion object {
        private val ACTIVE_STATUSES = setOf(
            TaskStatus.PENDING.name,
            TaskStatus.CLAIMED.name,
            TaskStatus.RUNNING.name,
        )
        private val HUMAN_FMT = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())
    }
}
