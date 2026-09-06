package com.example.data.task

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, (CASE WHEN reminderTimeMillis IS NULL THEN 1 ELSE 0 END), reminderTimeMillis ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY (CASE WHEN reminderTimeMillis IS NULL THEN 1 ELSE 0 END), reminderTimeMillis ASC, createdAt DESC")
    fun getActiveTasks(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TaskItem?

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' LIMIT 1")
    suspend fun findTaskByTitle(query: String): TaskItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskItem): Long

    @Update
    suspend fun updateTask(task: TaskItem)

    @Delete
    suspend fun deleteTask(task: TaskItem)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long): Int

    @Query("UPDATE tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun setTaskCompleted(id: Long, completed: Boolean): Int

    @Query("UPDATE tasks SET reminderWorkId = :workId WHERE id = :id")
    suspend fun updateWorkId(id: Long, workId: String?): Int
}
