package com.example.data.task

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.AssistantLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

sealed class TaskVoiceResult {
    data class Created(val task: TaskItem, val message: String) : TaskVoiceResult()
    data class Completed(val task: TaskItem, val message: String) : TaskVoiceResult()
    data class Deleted(val title: String, val message: String) : TaskVoiceResult()
    data class Listed(val tasks: List<TaskItem>, val message: String) : TaskVoiceResult()
    data class NotHandled(val reason: String) : TaskVoiceResult()
}

object TaskManager {
    private const val TAG = "TaskManager"
    private var appContext: Context? = null
    private var taskDatabase: TaskDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var tasksFlow: Flow<List<TaskItem>>? = null
        private set

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            taskDatabase = TaskDatabase.getDatabase(context.applicationContext)
            tasksFlow = taskDatabase?.taskDao()?.getAllTasks()
            AssistantLogger.i(TAG, "TaskManager initialized with Room & WorkManager")
        }
    }

    private fun getDao(): TaskDao {
        val db = taskDatabase ?: throw IllegalStateException("TaskManager is not initialized")
        return db.taskDao()
    }

    suspend fun getAllTasks(): List<TaskItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TaskItem>()
        // Return latest list
        val db = taskDatabase ?: return@withContext emptyList()
        // Simple query
        return@withContext db.taskDao().findTaskByTitle("")?.let { listOf(it) } ?: emptyList()
    }

    /**
     * Inserts a task and optionally schedules a WorkManager reminder.
     */
    suspend fun createTask(
        title: String,
        description: String = "",
        reminderTimeMillis: Long? = null,
        isVoiceCaptured: Boolean = true
    ): TaskItem = withContext(Dispatchers.IO) {
        val dao = getDao()
        val initialTask = TaskItem(
            title = title.trim(),
            description = description.trim(),
            reminderTimeMillis = reminderTimeMillis,
            isVoiceCaptured = isVoiceCaptured,
            isCompleted = false
        )
        val taskId = dao.insertTask(initialTask)
        var savedTask = initialTask.copy(id = taskId)

        // Schedule WorkManager reminder if due in future
        if (reminderTimeMillis != null && reminderTimeMillis > System.currentTimeMillis()) {
            val workId = scheduleWorkManagerReminder(savedTask)
            if (workId != null) {
                dao.updateWorkId(taskId, workId)
                savedTask = savedTask.copy(reminderWorkId = workId)
            }
        }

        AssistantLogger.i(TAG, "Persisted task #$taskId in Room: '${savedTask.title}' (reminder: ${savedTask.reminderTimeMillis})")
        savedTask
    }

    /**
     * Toggles or marks task completed.
     */
    suspend fun setTaskCompleted(taskId: Long, completed: Boolean) = withContext(Dispatchers.IO) {
        val dao = getDao()
        val task = dao.getTaskById(taskId)
        if (task != null) {
            dao.setTaskCompleted(taskId, completed)
            if (completed && task.reminderWorkId != null) {
                cancelReminderWork(task.reminderWorkId)
            }
            AssistantLogger.i(TAG, "Updated task #$taskId isCompleted=$completed")
        }
    }

    /**
     * Deletes a task from Room and cancels any scheduled WorkManager job.
     */
    suspend fun deleteTask(task: TaskItem) = withContext(Dispatchers.IO) {
        val dao = getDao()
        dao.deleteTask(task)
        if (task.reminderWorkId != null) {
            cancelReminderWork(task.reminderWorkId)
        }
        AssistantLogger.i(TAG, "Deleted task #${task.id} from Room")
    }

    /**
     * Updates an existing task.
     */
    suspend fun updateTask(task: TaskItem) = withContext(Dispatchers.IO) {
        val dao = getDao()
        dao.updateTask(task)
        // If reminder changed, reschedule
        if (task.reminderTimeMillis != null && task.reminderTimeMillis > System.currentTimeMillis() && !task.isCompleted) {
            if (task.reminderWorkId != null) {
                cancelReminderWork(task.reminderWorkId)
            }
            val newWorkId = scheduleWorkManagerReminder(task)
            if (newWorkId != null) {
                dao.updateWorkId(task.id, newWorkId)
            }
        }
    }

    private fun scheduleWorkManagerReminder(task: TaskItem): String? {
        val ctx = appContext ?: return null
        val reminderTime = task.reminderTimeMillis ?: return null
        val delayMillis = (reminderTime - System.currentTimeMillis()).coerceAtLeast(1000L)

        val inputData = Data.Builder()
            .putLong(ReminderNotificationWorker.KEY_TASK_ID, task.id)
            .putString(ReminderNotificationWorker.KEY_TASK_TITLE, task.title)
            .putString(ReminderNotificationWorker.KEY_TASK_DESC, task.description)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ReminderNotificationWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .addTag("task_reminder_${task.id}")
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "task_reminder_${task.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        val workId = workRequest.id.toString()
        AssistantLogger.i(TAG, "Scheduled WorkManager reminder for task #${task.id} in ${delayMillis / 1000}s (WorkId: $workId)")
        return workId
    }

    private fun cancelReminderWork(workId: String) {
        val ctx = appContext ?: return
        try {
            WorkManager.getInstance(ctx).cancelWorkById(UUID.fromString(workId))
            AssistantLogger.i(TAG, "Cancelled WorkManager reminder: $workId")
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "Failed to cancel work: ${e.message}")
        }
    }

    /**
     * Parses natural voice queries for task and reminder commands.
     */
    suspend fun handleVoiceQuery(rawQuery: String): TaskVoiceResult = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        val lower = query.lowercase().trim()

        // 1. Task listing queries
        if (isTaskListQuery(lower)) {
            val db = taskDatabase ?: return@withContext TaskVoiceResult.NotHandled("Database not ready")
            val tasks = mutableListOf<TaskItem>()
            // Query active tasks
            val cursor = db.openHelper.readableDatabase.query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY createdAt DESC")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                val desc = cursor.getString(cursor.getColumnIndexOrThrow("description"))
                val due = if (cursor.isNull(cursor.getColumnIndexOrThrow("reminderTimeMillis"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("reminderTimeMillis"))
                val comp = cursor.getInt(cursor.getColumnIndexOrThrow("isCompleted")) == 1
                val voice = cursor.getInt(cursor.getColumnIndexOrThrow("isVoiceCaptured")) == 1
                val wid = cursor.getString(cursor.getColumnIndexOrThrow("reminderWorkId"))
                val created = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
                tasks.add(TaskItem(id, title, desc, due, comp, voice, wid, created))
            }
            cursor.close()

            val msg = if (tasks.isEmpty()) {
                "You have no active tasks. You can say 'Remind me to buy groceries tomorrow' or 'Add task review code'."
            } else {
                val countText = if (tasks.size == 1) "1 active task" else "${tasks.size} active tasks"
                val taskListText = tasks.take(3).mapIndexed { index, task ->
                    val timeNotice = task.reminderTimeMillis?.let {
                        " (reminder set for ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))})"
                    } ?: ""
                    "${index + 1}: ${task.title}$timeNotice"
                }.joinToString(", ")
                "You have $countText. $taskListText."
            }
            return@withContext TaskVoiceResult.Listed(tasks, msg)
        }

        // 2. Complete / Finish task
        if (lower.startsWith("complete task") || lower.startsWith("finish task") || lower.startsWith("done with task") || lower.startsWith("check off task")) {
            val titleQuery = query.replaceFirst("(?i)complete task".toRegex(), "")
                .replaceFirst("(?i)finish task".toRegex(), "")
                .replaceFirst("(?i)done with task".toRegex(), "")
                .replaceFirst("(?i)check off task".toRegex(), "")
                .trim()
            val dao = getDao()
            val task = dao.findTaskByTitle(titleQuery)
            if (task != null) {
                setTaskCompleted(task.id, true)
                return@withContext TaskVoiceResult.Completed(task, "Completed task: '${task.title}'. Great job!")
            } else {
                return@withContext TaskVoiceResult.NotHandled("Could not find a task matching '$titleQuery'.")
            }
        }

        // 3. Delete / Remove task
        if (lower.startsWith("delete task") || lower.startsWith("remove task") || lower.startsWith("cancel reminder for")) {
            val titleQuery = query.replaceFirst("(?i)delete task".toRegex(), "")
                .replaceFirst("(?i)remove task".toRegex(), "")
                .replaceFirst("(?i)cancel reminder for".toRegex(), "")
                .trim()
            val dao = getDao()
            val task = dao.findTaskByTitle(titleQuery)
            if (task != null) {
                deleteTask(task)
                return@withContext TaskVoiceResult.Deleted(task.title, "Removed task: '${task.title}'.")
            } else {
                return@withContext TaskVoiceResult.NotHandled("Could not find a task matching '$titleQuery' to delete.")
            }
        }

        // 4. "Remind me to [X] in/at [Y]" OR "Add task [X]"
        if (isReminderOrTaskCreation(lower)) {
            val parsed = parseTaskDetails(query)
            val createdTask = createTask(
                title = parsed.title,
                description = parsed.description,
                reminderTimeMillis = parsed.dueTimeMillis,
                isVoiceCaptured = true
            )

            val timeFeedback = if (createdTask.reminderTimeMillis != null) {
                val formatted = SimpleDateFormat("h:mm a, MMMM d", Locale.getDefault()).format(Date(createdTask.reminderTimeMillis))
                " and scheduled a push reminder notification for $formatted"
            } else {
                ""
            }

            val speech = "Saved task: '${createdTask.title}'$timeFeedback."
            return@withContext TaskVoiceResult.Created(createdTask, speech)
        }

        TaskVoiceResult.NotHandled("Not a task command")
    }

    private fun isTaskListQuery(lower: String): Boolean {
        return lower == "show tasks" ||
               lower == "show my tasks" ||
               lower == "list tasks" ||
               lower == "list my tasks" ||
               lower == "what are my tasks" ||
               lower == "what tasks do i have" ||
               lower == "show reminders" ||
               lower == "show my reminders" ||
               lower == "my tasks"
    }

    private fun isReminderOrTaskCreation(lower: String): Boolean {
        return lower.startsWith("remind me ") ||
               lower.startsWith("remind me to ") ||
               lower.startsWith("reminder to ") ||
               lower.startsWith("set a reminder to ") ||
               lower.startsWith("add task ") ||
               lower.startsWith("create task ") ||
               lower.startsWith("new task ") ||
               lower.startsWith("task to ") ||
               lower.startsWith("add a task ") ||
               lower.startsWith("create a task ")
    }

    private data class ParsedTask(
        val title: String,
        val description: String,
        val dueTimeMillis: Long?
    )

    private fun parseTaskDetails(raw: String): ParsedTask {
        var text = raw.trim()
        val prefixes = listOf(
            "remind me to ", "remind me ", "reminder to ", "set a reminder to ",
            "add task ", "create task ", "new task ", "task to ", "add a task to ",
            "add a task ", "create a task to ", "create a task "
        )

        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }

        var dueMillis: Long? = null
        val cal = Calendar.getInstance()

        // Match: "in X minutes" / "in X minute" / "in X mins"
        val inMinutesMatch = "(?i)\\s+in\\s+(\\d+)\\s+(?:minutes?|mins?)".toRegex().find(text)
        if (inMinutesMatch != null) {
            val minutes = inMinutesMatch.groupValues[1].toIntOrNull() ?: 10
            cal.add(Calendar.MINUTE, minutes)
            dueMillis = cal.timeInMillis
            text = text.replace(inMinutesMatch.value, "").trim()
        }

        // Match: "in X hours" / "in X hour"
        val inHoursMatch = "(?i)\\s+in\\s+(\\d+)\\s+(?:hours?|hrs?)".toRegex().find(text)
        if (dueMillis == null && inHoursMatch != null) {
            val hours = inHoursMatch.groupValues[1].toIntOrNull() ?: 1
            cal.add(Calendar.HOUR_OF_DAY, hours)
            dueMillis = cal.timeInMillis
            text = text.replace(inHoursMatch.value, "").trim()
        }

        // Match: "at X:XX AM/PM" or "at X AM/PM" or "at X o'clock"
        val atTimeMatch = "(?i)\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?".toRegex().find(text)
        if (dueMillis == null && atTimeMatch != null) {
            val hourRaw = atTimeMatch.groupValues[1].toIntOrNull() ?: 9
            val minuteRaw = atTimeMatch.groupValues[2].toIntOrNull() ?: 0
            val amPm = atTimeMatch.groupValues[3].lowercase()

            var hour = hourRaw
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0

            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minuteRaw)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            // If time is earlier today, schedule for tomorrow
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            dueMillis = cal.timeInMillis
            text = text.replace(atTimeMatch.value, "").trim()
        }

        // Match "tomorrow"
        if (text.contains("tomorrow", ignoreCase = true)) {
            if (dueMillis == null) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                dueMillis = cal.timeInMillis
            } else {
                val reminderCal = Calendar.getInstance().apply { timeInMillis = dueMillis }
                if (reminderCal.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) {
                    reminderCal.add(Calendar.DAY_OF_YEAR, 1)
                    dueMillis = reminderCal.timeInMillis
                }
            }
            text = text.replace("(?i)\\s*tomorrow\\s*".toRegex(), " ").trim()
        }

        val cleanTitle = text.ifBlank { "Voice Reminder" }.replaceFirstChar { it.uppercase() }
        return ParsedTask(
            title = cleanTitle,
            description = "Captured via MJ voice command",
            dueTimeMillis = dueMillis
        )
    }
}
