package com.example.model

data class Playlist(
    val id: String,
    val name: String,
    val description: String,
    val trackIds: List<String> = emptyList(),
    val isSmartFlow: Boolean = false,
    val isSystem: Boolean = false,
    val gradientColors: List<Long> = listOf(0xFFA855F7, 0xFF3B82F6),
    val createdTimestamp: Long = System.currentTimeMillis()
)
