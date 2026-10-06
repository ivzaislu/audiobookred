package com.example.data.parser

import android.content.Context
import com.example.data.settings.ExternalServiceCredentialsStore

/** On-device Abred provider hub. No backend fallback is used here. */
object AndroidLiveParserLocator {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var instance: AndroidLiveParserHub? = null

    @Volatile
    private var externalServiceCredentialsStore: (() -> ExternalServiceCredentialsStore)? = null

    /**
     * Keep Application startup cheap: remember only the application Context here.
     * The provider hub is constructed once on first use; individual provider
     * parsers remain lazy inside that hub.
     */
    fun initialize(
        context: Context,
        externalServiceCredentialsStore: () -> ExternalServiceCredentialsStore,
    ) {
        if (appContext != null && this.externalServiceCredentialsStore != null) return
        synchronized(this) {
            if (appContext == null) appContext = context.applicationContext
            if (this.externalServiceCredentialsStore == null) {
                this.externalServiceCredentialsStore = externalServiceCredentialsStore
            }
        }
    }

    fun instanceOrNull(): AndroidLiveParserHub? {
        instance?.let { return it }
        val context = appContext ?: return null
        val credentialsStore = externalServiceCredentialsStore ?: return null
        return synchronized(this) {
            instance ?: AndroidLiveParserHub(
                context = context,
                externalServiceCredentialsStore = credentialsStore,
            ).also { instance = it }
        }
    }
}
