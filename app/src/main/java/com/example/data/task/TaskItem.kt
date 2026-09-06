package com.example.data.task

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val reminderTimeMillis: Long? = null,
    val isCompleted: Boolean = false,
    val isVoiceCaptured: Boolean = true,
    val reminderWorkId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
