package com.acktarius.hnsgo.browser

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history",
    indices = [Index(value = ["url"])] // Non-unique index for faster lookups
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String,
    val favicon: String? = null, // Favicon URL or data URI
    val visitedAt: Long = System.currentTimeMillis()
)
