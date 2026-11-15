package com.zxsofa.data

/** Una revista en PDF de la biblioteca. */
data class Magazine(
    val id: Long = 0,
    val title: String,
    val path: String,
    val thumbPath: String? = null,
    val pages: Int = 0,
    val lastPage: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)
