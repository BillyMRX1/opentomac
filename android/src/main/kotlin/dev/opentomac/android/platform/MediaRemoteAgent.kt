package dev.opentomac.android.platform

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.opentomac.android.service.OpentomacNotificationListenerService
import dev.opentomac.shared.protocol.MediaControl
import dev.opentomac.shared.protocol.MediaNowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaRemoteAgent(
    context: Context,
    private val scope: CoroutineScope,
    private val send: suspend (MediaNowPlaying) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(
        appContext,
        OpentomacNotificationListenerService::class.java,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        updateController(sessions.orEmpty())
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            schedulePush()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            schedulePush()
        }
    }

    @Volatile
    private var controller: MediaController? = null
    private var started = false
    private var listenerRegistered = false
    private var pushJob: Job? = null
    private var lastSent: MediaNowPlaying? = null

    fun start() {
        if (started) return
        started = true
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                mainHandler,
            )
            listenerRegistered = true
            updateController(sessionManager.getActiveSessions(listenerComponent))
        } catch (cause: SecurityException) {
            handleMissingAccess(cause)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        pushJob?.cancel()
        pushJob = null
        runCatching { controller?.unregisterCallback(controllerCallback) }
        controller = null
        if (listenerRegistered) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            listenerRegistered = false
        }
    }

    /** Re-queries and force-publishes state so a newly connected peer cannot miss it. */
    fun pushCurrentState() {
        if (!started) return
        try {
            updateController(sessionManager.getActiveSessions(listenerComponent), force = true)
        } catch (cause: SecurityException) {
            handleMissingAccess(cause)
        }
    }

    fun onControl(message: MediaControl) {
        runCatching {
            when (message.command) {
                "play_pause" -> controller?.let { active ->
                    if (active.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        active.transportControls.pause()
                    } else {
                        active.transportControls.play()
                    }
                }
                "next" -> controller?.transportControls?.skipToNext()
                "previous" -> controller?.transportControls?.skipToPrevious()
                "volume_up" -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    0,
                )
                "volume_down" -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    0,
                )
            }
        }.onFailure { Log.w(LOG_TAG, "media control failed: ${message.command}", it) }
    }

    @Synchronized
    private fun updateController(sessions: List<MediaController>, force: Boolean = false) {
        if (!started) return
        val primary = sessions.firstOrNull()
        if (primary?.sessionToken != controller?.sessionToken) {
            runCatching { controller?.unregisterCallback(controllerCallback) }
            controller = primary
            runCatching { primary?.registerCallback(controllerCallback, mainHandler) }
        }
        schedulePush(force)
    }

    @Synchronized
    private fun schedulePush(force: Boolean = false) {
        if (!started) return
        pushJob?.cancel()
        pushJob = scope.launch {
            if (!force) delay(STATE_DEBOUNCE_MS)
            val state = currentState()
            val shouldSend = synchronized(this@MediaRemoteAgent) {
                if (!force && state == lastSent) {
                    false
                } else {
                    lastSent = state
                    true
                }
            }
            if (shouldSend) send(state)
        }
    }

    private fun currentState(): MediaNowPlaying {
        val active = controller ?: return NO_SESSION
        val metadata = active.metadata
        return MediaNowPlaying(
            appName = appName(active.packageName),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING,
            hasSession = true,
        )
    }

    private fun appName(packageName: String): String = runCatching {
        val info = appContext.packageManager.getApplicationInfo(packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun handleMissingAccess(cause: SecurityException) {
        Log.w(LOG_TAG, "media sessions unavailable: notification access missing", cause)
        updateController(emptyList(), force = true)
    }

    private companion object {
        const val LOG_TAG = "opentomac"
        const val STATE_DEBOUNCE_MS = 100L
        val NO_SESSION = MediaNowPlaying(
            appName = "",
            title = "",
            artist = "",
            isPlaying = false,
            hasSession = false,
        )
    }
}
