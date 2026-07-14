package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.PermissionMode
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class TaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
}

data class Task(
    val id: String,
    val subject: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.PENDING,
    val owner: String? = null,
    val dependsOn: List<String> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val activeForm: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class Team(
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

object TaskManager {
    private val tasks = ConcurrentHashMap<String, Task>()
    val teams = ConcurrentHashMap<String, Team>()
    @Volatile
    var activeTeam: String? = null

    private var counter = AtomicInteger(0)

    fun createTask(subject: String, description: String = "", dependsOn: List<String> = emptyList(),
                   status: TaskStatus = TaskStatus.PENDING, activeForm: String = "",
                   metadata: Map<String, String> = emptyMap(), blockedBy: List<String> = emptyList()): Task {
        val id = "task-${counter.incrementAndGet()}"
        val task = Task(id = id, subject = subject, description = description, status = status,
            dependsOn = dependsOn, blockedBy = blockedBy, metadata = metadata, activeForm = activeForm)
        tasks[id] = task
        saveToDisk(task)
        return task
    }

    fun restoreTask(id: String, subject: String, description: String, status: String, dependsOn: List<String>,
                    owner: String? = null, activeForm: String = "", metadata: Map<String, String> = emptyMap(),
                    blockedBy: List<String> = emptyList()) {
        if (!tasks.containsKey(id)) {
            val task = Task(
                id = id, subject = subject, description = description,
                status = try { TaskStatus.valueOf(status) } catch (_: Exception) { TaskStatus.PENDING },
                dependsOn = dependsOn, owner = owner,
                activeForm = activeForm, metadata = metadata, blockedBy = blockedBy,
            )
            tasks[id] = task
        }
    }

    fun getTask(id: String): Task? = tasks[id]
    fun listTasks(): List<Task> = tasks.values.toList().sortedBy { it.createdAt }

    fun updateTask(id: String, status: TaskStatus? = null, owner: String? = null,
                   description: String? = null, dependsOn: List<String>? = null,
                   activeForm: String? = null, metadata: Map<String, String>? = null,
                   addBlocks: List<String>? = null, addBlockedBy: List<String>? = null): Task? {
        val t = tasks[id] ?: return null
        tasks[id] = t.copy(
            status = status ?: t.status,
            owner = owner ?: t.owner,
            description = description ?: t.description,
            dependsOn = (dependsOn ?: t.dependsOn) + (addBlocks ?: emptyList()),
            activeForm = activeForm ?: t.activeForm,
            metadata = metadata ?: t.metadata,
            blockedBy = t.blockedBy + (addBlockedBy ?: emptyList()),
        )
        return tasks[id]
    }

    fun stopTask(id: String): Task? = updateTask(id, TaskStatus.CANCELLED)

    // ── 文件持久化（s12 标准：.tasks/{id}.json）──
    private var tasksDir: File? = null

    fun setPersistenceDir(dir: File) {
        tasksDir = dir
        dir.mkdirs()
        loadFromDisk()
    }

    private fun saveToDisk(task: Task) {
        val dir = tasksDir ?: return
        val file = File(dir, "${task.id}.json")
        try {
            file.writeText(
                buildJsonObject {
                    put("id", task.id)
                    put("subject", task.subject)
                    put("description", task.description)
                    put("status", task.status.name)
                    put("owner", task.owner ?: "")
                    put("dependsOn", buildJsonArray { task.dependsOn.forEach { add(it) } })
                    put("blockedBy", buildJsonArray { task.blockedBy.forEach { add(it) } })
                    put("activeForm", task.activeForm)
                    put("createdAt", task.createdAt)
                }.toString()
            )
        } catch (_: Exception) {}
    }

    private fun saveAllToDisk() {
        tasks.values.forEach { saveToDisk(it) }
    }

    private fun loadFromDisk() {
        val dir = tasksDir ?: return
        if (!dir.exists()) return
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { file ->
            try {
                val json = Json.parseToJsonElement(file.readText()).jsonObject
                val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val subject = json["subject"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val description = json["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val status = json["status"]?.jsonPrimitive?.contentOrNull ?: "PENDING"
                val owner = json["owner"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val dependsOn = json["dependsOn"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val blockedBy = json["blockedBy"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val activeForm = json["activeForm"]?.jsonPrimitive?.contentOrNull ?: ""
                val createdAt = json["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                val count = counter.get()
                val numId = id.removePrefix("task-").toIntOrNull()
                if (numId != null && numId > count) counter.set(numId)
                val task = Task(
                    id = id, subject = subject, description = description,
                    status = try { TaskStatus.valueOf(status) } catch (_: Exception) { TaskStatus.PENDING },
                    owner = owner, dependsOn = dependsOn, blockedBy = blockedBy,
                    activeForm = activeForm, createdAt = createdAt,
                )
                tasks[id] = task
            } catch (_: Exception) {}
        }
    }

    /** 持久化包装：createTask 之后自动保存 */
    private fun createTaskAndSave(subject: String, description: String, dependsOn: List<String>,
                                   status: TaskStatus, activeForm: String,
                                   metadata: Map<String, String>, blockedBy: List<String>): Task {
        val task = createTask(subject, description, dependsOn, status, activeForm, metadata, blockedBy)
        saveToDisk(task)
        return task
    }

    fun taskOutput(id: String): String? {
        val t = tasks[id] ?: return null
        return buildString {
            appendLine("[${t.id}] ${t.subject}")
            appendLine("Status: ${t.status.name.lowercase()}")
            if (t.owner != null) appendLine("Owner: ${t.owner}")
            if (t.description.isNotBlank()) appendLine("Description: ${t.description}")
            if (t.activeForm.isNotBlank()) appendLine("Progress: ${t.activeForm}")
            if (t.dependsOn.isNotEmpty()) appendLine("Blocks: ${t.dependsOn.joinToString(", ")}")
            if (t.blockedBy.isNotEmpty()) appendLine("Blocked by: ${t.blockedBy.joinToString(", ")}")
            if (t.metadata.isNotEmpty()) appendLine("Metadata: ${t.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
    }

    // Lightweight todo list (separate from Task system)
    data class TodoItem(val id: String, val subject: String, val status: String = "pending", val createdAt: Long = System.currentTimeMillis())
    private val todos = ConcurrentHashMap<String, TodoItem>()
    private val todoCounter = AtomicInteger(0)
    private val roundsSinceTodoUpdate = AtomicInteger(0)
    private val NAG_THRESHOLD = 5

    fun createTodo(subject: String, status: String = "pending"): TodoItem {
        val id = "todo-${todoCounter.incrementAndGet()}"
        val item = TodoItem(id = id, subject = subject, status = status)
        todos[id] = item
        return item
    }

    fun listTodos(): List<TodoItem> = todos.values.toList().sortedBy { it.createdAt }

    fun createTeam(name: String, description: String = ""): Team {
        val team = Team(name = name, description = description)
        teams[name] = team; activeTeam = name
        return team
    }

    fun deleteTeam(name: String) { teams.remove(name); if (activeTeam == name) activeTeam = null }

    // Fork management
    data class ForkInfo(val name: String, val goal: String, val status: ForkStatus = ForkStatus.RUNNING, val result: String = "")
    enum class ForkStatus { RUNNING, DONE, FAILED }

    private val forks = ConcurrentHashMap<String, ForkInfo>()
    private val forkNotifications = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun registerFork(name: String, goal: String): Boolean {
        if (forks.containsKey(name)) return false
        forks[name] = ForkInfo(name = name, goal = goal); return true
    }

    fun completeFork(name: String, result: String) {
        forks.compute(name) { _, v -> v?.copy(status = ForkStatus.DONE, result = result) }
        forkNotifications.add(name)
    }

    fun failFork(name: String, error: String) {
        forks.compute(name) { _, v -> v?.copy(status = ForkStatus.FAILED, result = error) }
        forkNotifications.add(name)
    }

    fun getForkStatus(name: String): ForkInfo? = forks[name]
    fun listForks(): List<ForkInfo> = forks.values.toList()

    fun consumeForkNotifications(): List<ForkInfo> {
        val names = mutableListOf<String>()
        while (true) { val name = forkNotifications.poll() ?: break; names.add(name) }
        return names.mapNotNull { forks[it] }
    }

    // Agent message system
    data class Message(val id: String, val from: String, val to: String, val content: String, val timestamp: Long = System.currentTimeMillis())
    private val messages = java.util.concurrent.ConcurrentLinkedQueue<Message>()
    private val msgCounter = java.util.concurrent.atomic.AtomicInteger(0)

    fun sendMessage(from: String, to: String, content: String): Message {
        val msg = Message(id = "msg-${msgCounter.incrementAndGet()}", from = from, to = to, content = content)
        messages.add(msg); return msg
    }


    fun getTodos(): List<TodoItem> = todos.values.toList()

    fun getPlanSummary(): String {
        if (todos.isEmpty()) return ""
        return buildString {
            appendLine("## Current Plan")
            todos.values.toList().forEachIndexed { i, item ->
                val icon = when (item.status) {
                    "completed" -> "✅"
                    "in_progress" -> "▶"
                    else -> "⬜"
                }
                appendLine("$icon [$i] ${item.subject} (${item.status})")
            }
        }
    }

    fun shouldNag(): Boolean {
        if (todos.isEmpty()) return false
        val rounds = roundsSinceTodoUpdate.incrementAndGet()
        return rounds > NAG_THRESHOLD
    }

    fun resetNag() {
        roundsSinceTodoUpdate.set(0)
    }

    fun clear() {
        todos.clear()
        roundsSinceTodoUpdate.set(0)
    }

    private fun formatTodos(): String {
        if (todos.isEmpty()) return "Plan cleared (0 tasks)"
        return buildString {
            appendLine("Updated ${todos.size} tasks:")
            todos.values.toList().forEach { item ->
                val icon = when (item.status) {
                    "completed" -> "✓"
                    "in_progress" -> "▸"
                    else -> " "
                }
                appendLine("  [$icon] ${item.subject} [${item.status}]")
            }
        }
    }
}

fun createTaskTools(): List<Tool> = buildList {
    addAll(listOf(
    Tool(name = "task_create", description = "Create a new task in the task list. Use for complex multi-step tasks (3+ steps).\n\nWhen to use:\n- Complex multi-step tasks requiring 3+ steps\n- Non-trivial tasks requiring careful planning\n- User explicitly requests todo list\n- User provides multiple tasks\n- After receiving new instructions\n\nWhen NOT to use:\n- Single straightforward task\n- Trivial tasks with no organizational benefit\n- Purely conversational requests\n\nTasks created with status pending. Use task_update to change status.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("subject", buildJsonObject { put("type", "string"); put("description", "Task title") })
                put("description", buildJsonObject { put("type", "string"); put("description", "Details") })
                put("active_form", buildJsonObject { put("type", "string"); put("description", "Progress text shown when in_progress") })
                put("depends_on", buildJsonObject { put("type", "string"); put("description", "Comma-separated task IDs this depends on") })
            }, required = listOf("subject"))
        },
        execute = { args ->
            val obj = args.jsonObject; val subject = obj["subject"]?.jsonPrimitive?.contentOrNull ?: error("subject required")
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val af = obj["active_form"]?.jsonPrimitive?.contentOrNull ?: ""
            val deps = obj["depends_on"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val task = TaskManager.createTask(subject, desc, deps, activeForm = af)
            listOf(UIMessagePart.Text("[${task.id}] created: ${task.subject}"))
        },
    ),
    Tool(name = "task_get", description = "Get details of a task by ID. Returns the full task including status, description, owner, and dependencies.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Task ID") })
            }, required = listOf("id"))
        },
        execute = { args ->
            val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id required")
            listOf(UIMessagePart.Text(TaskManager.taskOutput(id) ?: error("Task $id not found")))
        },
    ),
    Tool(name = "task_list", description = "List all tasks, optionally filtered by status or owner. Shows task IDs, subjects, statuses, and owners.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("status", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("pending"); add("in_progress"); add("completed"); add("failed"); add("cancelled") }) })
                put("owner", buildJsonObject { put("type", "string"); put("description", "Filter by owner") })
            })
        },
        execute = { args ->
            val fs = args.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            val fo = args.jsonObject["owner"]?.jsonPrimitive?.contentOrNull
            val filtered = TaskManager.listTasks().filter { t ->
                (fs == null || t.status.name.lowercase() == fs) &&
                (fo == null || t.owner == fo)
            }
            if (filtered.isEmpty()) return@Tool listOf(UIMessagePart.Text("(no tasks)"))
            listOf(UIMessagePart.Text(filtered.joinToString("\n") { t ->
                val icon = when (t.status) { TaskStatus.COMPLETED -> "✅"; TaskStatus.IN_PROGRESS -> "🔄"; TaskStatus.FAILED -> "❌"; TaskStatus.CANCELLED -> "🚫"; else -> "⏳" }
                "$icon ${t.id}: ${t.subject}${if (t.owner != null) " [${t.owner}]" else ""}"
            }))
        },
    ),
    Tool(name = "task_mgmt", description = "Manage tasks: update status, stop, view output, manage teams and todos, check dependencies, and create structured task packets.\n\nActions: update, stop, output, todo, team_create, team_delete, run_packet, dag, can_start",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("update"); add("stop"); add("output"); add("todo")
                        add("team_create"); add("team_delete"); add("run_packet")
                        add("dag"); add("can_start")
                    })
                    put("description", "Operation: update=change task status/details, stop=cancel task, output=view result, todo=create lightweight todos, team_create/delete=manage teams, run_packet=structured task, dag=dependency graph, can_start=check deps")
                })
                put("id", buildJsonObject { put("type", "string"); put("description", "Task ID (used by: update, stop, output, can_start)") })
                put("status", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("pending"); add("in_progress"); add("completed"); add("failed"); add("cancelled") }); put("description", "New status (used by: update)") })
                put("owner", buildJsonObject { put("type", "string"); put("description", "Assign to agent (used by: update)") })
                put("description", buildJsonObject { put("type", "string"); put("description", "Updated description (used by: update)") })
                put("active_form", buildJsonObject { put("type", "string"); put("description", "Progress text (used by: update)") })
                put("depends_on", buildJsonObject { put("type", "string"); put("description", "Comma-separated task IDs this blocks (used by: update)") })
                put("blocked_by", buildJsonObject { put("type", "string"); put("description", "Comma-separated task IDs blocking this (used by: update)") })
                put("metadata", buildJsonObject { put("type", "string"); put("description", "JSON key=value pairs (used by: update)") })
                put("todos", buildJsonObject {
                    put("type", "array"); put("description", "Todo items (used by: todo)")
                    put("items", buildJsonObject {
                        put("type", "object"); put("properties", buildJsonObject {
                            put("subject", buildJsonObject { put("type", "string") })
                            put("status", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("pending"); add("in_progress"); add("completed") }) })
                        }); put("required", buildJsonArray { add("subject") })
                    })
                })
                put("team_name", buildJsonObject { put("type", "string"); put("description", "Team name (used by: team_create, team_delete)") })
                put("team_description", buildJsonObject { put("type", "string"); put("description", "Team purpose (used by: team_create)") })
                put("objective", buildJsonObject { put("type", "string"); put("description", "What to accomplish (used by: run_packet)") })
                put("scope", buildJsonObject { put("type", "string"); put("description", "Module, file, or repo (used by: run_packet)") })
                put("acceptance_tests", buildJsonObject { put("type", "string"); put("description", "Comma-separated tests (used by: run_packet)") })
                put("commit_policy", buildJsonObject { put("type", "string"); put("description", "single_commit | no_commit (used by: run_packet)") })
                put("escalation_policy", buildJsonObject { put("type", "string"); put("description", "On-failure target (used by: run_packet)") })
                put("task_id", buildJsonObject { put("type", "string"); put("description", "Kanban task ID (used by: claim)") })
            }, required = listOf("action"))
        },
        execute = { args ->
            val obj = args.jsonObject
            val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: error("action required")
            when (action) {
                "update" -> {
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("id required")
                    val meta = obj["metadata"]?.jsonPrimitive?.contentOrNull?.split(",")?.mapNotNull { it.trim().split("=", limit=2).let { if (it.size == 2) it[0].trim() to it[1].trim() else null } }?.toMap()
                    val task = TaskManager.updateTask(id = id,
                        status = obj["status"]?.jsonPrimitive?.contentOrNull?.let { TaskStatus.valueOf(it.uppercase()) },
                        owner = obj["owner"]?.jsonPrimitive?.contentOrNull,
                        description = obj["description"]?.jsonPrimitive?.contentOrNull,
                        dependsOn = obj["depends_on"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() },
                        activeForm = obj["active_form"]?.jsonPrimitive?.contentOrNull,
                        metadata = meta,
                        addBlockedBy = obj["blocked_by"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() },
                    ) ?: error("Task $id not found")
                    listOf(UIMessagePart.Text("[${task.id}] updated: ${task.status.name.lowercase()}"))
                }
                "stop" -> { val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("id required"); TaskManager.stopTask(id) ?: error("Task $id not found"); listOf(UIMessagePart.Text("Task $id stopped")) }
                "output" -> { val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("id required"); listOf(UIMessagePart.Text(TaskManager.taskOutput(id) ?: error("Task $id not found"))) }
                "todo" -> {
                    val todos = obj["todos"]?.jsonArray ?: error("todos required")
                    val results = todos.map { item -> val itemObj = item.jsonObject; val s = itemObj["subject"]?.jsonPrimitive?.contentOrNull ?: ""; val st = itemObj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"; TaskManager.createTodo(s, st); "${when (st) { "completed" -> "✅"; "in_progress" -> "🔄"; else -> "⏳" }} $s" }
                    TaskManager.resetNag(); listOf(UIMessagePart.Text(results.joinToString("\n")))
                }
                "team_create" -> { val name = obj["team_name"]?.jsonPrimitive?.contentOrNull ?: error("team_name required"); val desc = obj["team_description"]?.jsonPrimitive?.contentOrNull ?: ""; TaskManager.createTeam(name, desc); listOf(UIMessagePart.Text("Team '$name' created")) }
                "team_delete" -> { val name = obj["team_name"]?.jsonPrimitive?.contentOrNull ?: error("team_name required"); TaskManager.deleteTeam(name); listOf(UIMessagePart.Text("Team '$name' deleted")) }
                "run_packet" -> {
                    val objective = obj["objective"]?.jsonPrimitive?.contentOrNull ?: error("objective required")
                    val scope = obj["scope"]?.jsonPrimitive?.contentOrNull ?: ""; val tests = obj["acceptance_tests"]?.jsonPrimitive?.contentOrNull ?: ""
                    val task = TaskManager.createTask(subject = objective, description = "Scope: $scope\nTests: $tests\nCommit: single_commit")
                    listOf(UIMessagePart.Text(buildJsonObject { put("task_id", task.id); put("status", "created"); put("objective", objective) }.toString()))
                }
                "dag" -> {
                    val tasks = TaskManager.listTasks()
                    val output = buildString { appendLine("Task Dependency Graph:"); tasks.filter { it.dependsOn.isNotEmpty() }.forEach { t -> appendLine("  ${t.id} [${t.status.name.lowercase()}]"); t.dependsOn.forEach { dep -> appendLine("    └─ depends on: $dep") } } }
                    listOf(UIMessagePart.Text(output.ifBlank { "No dependencies defined." }))
                }
                "can_start" -> {
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("id required")
                    val task = TaskManager.getTask(id) ?: error("Task $id not found")
                    val blocked = task.blockedBy.filter { b -> TaskManager.getTask(b)?.status != TaskStatus.COMPLETED }
                    if (blocked.isEmpty()) listOf(UIMessagePart.Text("Task $id is ready to start. All dependencies satisfied."))
                    else listOf(UIMessagePart.Text("Task $id is blocked by:\n" + blocked.mapNotNull { b -> TaskManager.getTask(b)?.let { "${it.id}: ${it.subject} (${it.status.name.lowercase()})" } }.joinToString("\n")))
                }
                else -> error("Unknown action: $action")
            }
        },
    ),
    ))
}
