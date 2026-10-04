package com.example.ui

internal fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe Б"
    val kib = safe / 1024.0
    if (kib < 1024.0) return String.format("%.1f КБ", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format("%.1f МБ", mib)
    return String.format("%.2f ГБ", mib / 1024.0)
}
