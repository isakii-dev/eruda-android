package io.liriliri.eruda.data

data class Bookmark(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)