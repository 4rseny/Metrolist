/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.music.App
import com.metrolist.music.constants.AnonLoginEnabledKey
import com.metrolist.music.constants.AnonWorkerUrlKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.DEFAULT_ANON_WORKER_URL
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject

@Serializable
private data class SessionInfoResponse(
    val dataSyncId: String? = null,
    val visitorData: String? = null,
)

@Serializable
private data class CredentialsResponse(
    val cookie: String? = null,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
)

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val syncUtils: SyncUtils,
) : ViewModel() {

    private val httpClient = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Logout user and clear all synced content to prevent data mixing between accounts
     */
    fun logoutAndClearSyncedContent(context: Context, onCookieChange: (String) -> Unit, onAnonLoginChange: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // Clear all YouTube Music synced content first
            syncUtils.clearAllSyncedContent()

            // Then clear account preferences
            App.forgetAccount(context)

            // Clear anon login state
            context.dataStore.edit { settings ->
                settings[AnonLoginEnabledKey] = false
            }
            YouTube.isAnonLogin = false
            YouTube.appVisitorData = null

            // Clear cookie in UI
            withContext(Dispatchers.Main) {
                onCookieChange("")
                onAnonLoginChange(false)
            }

            Timber.d("Logout complete - cleared auth and anon login state")
        }
    }

    /**
     * Fetch full credentials from the worker for direct YouTube API access
     */
    private suspend fun fetchCredentialsFromWorker(workerUrl: String): CredentialsResponse? {
        return try {
            val url = "$workerUrl/credentials?refresh=1"
            Timber.d("Fetching credentials from: $url")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            Timber.d("Credentials response code: ${response.code}")
            if (response.isSuccessful) {
                val body = response.body?.string()
                Timber.d("Credentials response body length: ${body?.length}")
                Timber.d("Credentials body end: ${body?.takeLast(200)}")
                if (body != null) {
                    val parsed = json.decodeFromString<CredentialsResponse>(body)
                    Timber.d("Parsed credentials: cookie=${parsed.cookie?.take(30)}, visitorData=${parsed.visitorData}, dataSyncId=${parsed.dataSyncId}")
                    parsed
                } else null
            } else {
                Timber.w("Failed to fetch credentials: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching credentials from worker")
            null
        }
    }

    /**
     * Enable anonymous login - fetches credentials from worker and uses them directly
     * This works exactly like Google login - app makes direct requests with the credentials
     */
    fun enableAnonLogin(context: Context, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("Enabling anonymous login")

            // Clear any existing login first
            App.forgetAccount(context)

            // Fetch full credentials from worker
            val credentials = fetchCredentialsFromWorker(DEFAULT_ANON_WORKER_URL)
            if (credentials?.cookie == null) {
                Timber.e("Failed to fetch credentials from worker")
                withContext(Dispatchers.Main) {
                    onComplete()
                }
                return@launch
            }

            Timber.d("Got credentials from worker: cookie=${credentials.cookie.take(50)}..., visitorData=${credentials.visitorData?.take(20)}..., dataSyncId=${credentials.dataSyncId}")

            // Store credentials locally - same as Google login
            context.dataStore.edit { settings ->
                settings[AnonLoginEnabledKey] = true
                settings[AnonWorkerUrlKey] = DEFAULT_ANON_WORKER_URL
                settings[InnerTubeCookieKey] = credentials.cookie
                credentials.visitorData?.let { settings[VisitorDataKey] = it }
                credentials.dataSyncId?.let { settings[DataSyncIdKey] = it }
            }

            // Apply to YouTube object immediately - same as Google login
            YouTube.cookie = credentials.cookie
            YouTube.visitorData = credentials.visitorData
            YouTube.dataSyncId = credentials.dataSyncId
            // Don't use worker URL for proxying - app makes direct requests now
            YouTube.anonWorkerUrl = null
            // Mark as anon login for PoToken handling
            YouTube.isAnonLogin = true

            // Fetch fresh visitorData for PoToken generation
            // This is needed because PoToken must be generated in the same context as visitorData
            Timber.d("Fetching fresh app visitorData for PoToken...")
            YouTube.fetchFreshVisitorData()
            Timber.d("App visitorData: ${YouTube.appVisitorData?.take(30)}...")

            Timber.d("Anonymous login enabled - YouTube.cookie set: ${YouTube.cookie?.take(30)}..., dataSyncId: ${YouTube.dataSyncId}, visitorData: ${YouTube.visitorData?.take(20)}..., appVisitorData: ${YouTube.appVisitorData?.take(20)}...")

            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    /**
     * Disable anonymous login - clears all credentials
     */
    fun disableAnonLogin(context: Context, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("Disabling anonymous login")

            // Clear all credentials - same as logout
            App.forgetAccount(context)

            // Also clear the anon login flag
            context.dataStore.edit { settings ->
                settings[AnonLoginEnabledKey] = false
            }

            // Clear anon login state
            YouTube.isAnonLogin = false
            YouTube.appVisitorData = null

            Timber.d("Anonymous login disabled, credentials cleared")

            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
