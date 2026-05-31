package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meetings")
data class Meeting(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val timestamp: Long,
    val transcript: String,
    val summary: String? = null,
    val actionItems: String? = null,
    val audioPath: String? = null,
    val durationSec: Int = 0,
    val isFavorite: Boolean = false
)
