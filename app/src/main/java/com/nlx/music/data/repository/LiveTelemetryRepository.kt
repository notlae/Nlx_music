package com.nlx.music.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nlx.music.data.local.SettingsPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class TelemetryPingPayload(
    val song_id: String = "",
    val title: String = "",
    val artist: String = "",
    val cover_url: String = "",
    val client: String = "android",
)

/**
 * Links native playback on NLX_ MUSIC Android to the NLX_ MUSIC Live Telemetry platform
 * (telemetry.clashprojects.qd.je).
 *
 * Anonymous, zero-IP logging, ephemeral 60s RAM bucket:
 * Pings report currently playing track metadata so active listeners appear
 * on the global real-time vector map.
 */
@Singleton
class LiveTelemetryRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsPreferences: SettingsPreferences,
) {
    companion object {
        const val TELEMETRY_BASE_URL = "https://telemetry.clashprojects.qd.je"
        const val PING_ENDPOINT = "$TELEMETRY_BASE_URL/api/telemetry/ping"
        private const val TAG = "LiveTelemetry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var lastPingAtMs = 0L
    private var lastPingKey: String? = null

    /**
     * Submit an anonymous playback tick for the currently playing track.
     * Pings are debounced to prevent spamming the backend rate limiter (max 10 req/min).
     */
    suspend fun ping(
        title: String,
        artist: String,
        songId: String? = null,
        artworkUrl: String? = null,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext

        val settings = runCatching { settingsPreferences.settings.first() }.getOrNull()
        if (settings != null && !settings.liveTelemetryEnabled) {
            return@withContext
        }

        val key = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        val now = System.currentTimeMillis()

        // Debounce: don't ping more than once every 30 seconds for the same song unless forced
        if (!force && key == lastPingKey && (now - lastPingAtMs) < 30_000L) {
            return@withContext
        }

        val payload = TelemetryPingPayload(
            song_id = songId ?: key,
            title = title.trim(),
            artist = artist.trim(),
            cover_url = artworkUrl ?: "",
            client = "android",
        )

        try {
            val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(PING_ENDPOINT)
                .post(body)
                .header("User-Agent", "NLX_MUSIC-Android/1.0")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    lastPingAtMs = now
                    lastPingKey = key
                    Log.d(TAG, "Live telemetry acknowledged: $title by $artist (HTTP ${response.code})")
                } else {
                    Log.w(TAG, "Live telemetry non-2xx status: ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live telemetry ping error: ${e.message}")
        }
    }

    /**
     * Opens the live map web application in a Chrome Custom Tab or external browser.
     */
    fun openLiveMap(context: Context) {
        runCatching {
            val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(context, Uri.parse(TELEMETRY_BASE_URL))
        }.onFailure {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEMETRY_BASE_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
