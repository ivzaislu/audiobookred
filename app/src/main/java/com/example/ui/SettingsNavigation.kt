package com.example.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
internal enum class SettingsDestination : NavKey {
    // Legacy values stay serializable so a restored back stack from an older APK
    // can still be decoded. Profile/Sync route to current local Storage; current
    // standalone UI emits only Storage, Player and ExternalServices.
    Profile,
    Sync,
    Storage,
    Player,
    ExternalServices,
}
