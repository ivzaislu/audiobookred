package com.example.data.backup

/**
 * Keeps production restore strict while allowing the side-by-side debug package
 * to import a backup created by the corresponding production application.
 */
internal fun backupApplicationIdIsAccepted(
    backupApplicationId: String,
    currentApplicationId: String,
    debugBuild: Boolean,
): Boolean {
    if (backupApplicationId == currentApplicationId) return true
    if (!debugBuild || !currentApplicationId.endsWith(DEBUG_APPLICATION_ID_SUFFIX)) return false

    val productionApplicationId = currentApplicationId.removeSuffix(DEBUG_APPLICATION_ID_SUFFIX)
    return productionApplicationId.isNotBlank() && backupApplicationId == productionApplicationId
}

private const val DEBUG_APPLICATION_ID_SUFFIX = ".debug"
