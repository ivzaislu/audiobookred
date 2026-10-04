package com.example.data.local

internal fun resetAbredDatabaseSingleton(ignoreCloseFailure: Boolean = false) {
    val field = AbredDatabase::class.java.getDeclaredField("instance")
    field.isAccessible = true
    val database = field.get(null) as? AbredDatabase
    if (ignoreCloseFailure) {
        runCatching { database?.close() }
    } else {
        database?.close()
    }
    field.set(null, null)
}
