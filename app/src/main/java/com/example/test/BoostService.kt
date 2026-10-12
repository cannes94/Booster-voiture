package com.example.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Service manuel : tu le lances avant de rouler, il tient l'effet
 * pendant Spotify / YouTube Music, y compris sous Android Auto
 * (audio toujours décodé côté téléphone en STREAM_MUSIC).
 * Pas d'auto-start, pas de widget.
 */
class BoostService : Service() {

    companion object {
        const val CHANNEL_ID = "booster_channel"
        const val NOTIF_ID = 11
        const val PREFS = "booster"

        const val ACTION_START = "com.example.test.START"
        const val ACTION_STOP = "com.example.test.STOP"
        const val ACTION_SET_BOOST = "com.example.test.SET_BOOST"
        const val ACTION_SET_VOICE = "com.example.test.SET_VOICE"
        const val ACTION_INC = "com.example.test.INC"
        const val ACTION_DEC = "com.example.test.DEC"
        const val ACTION_TOGGLE = "com.example.test.TOGGLE"

        const val EXTRA_BOOST = "boost"
        const val EXTRA_VOICE = "voice"
        const val KEY_BOOST = "boost"
        const val KEY_VOICE = "voice"
        const val KEY_ON = "on"
        const val KEY_PREV_VOL = "prev_vol"
        const val KEY_LAST_GAIN = "last_gain_mb"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_EQ = "last_eq"

        fun intent(ctx: Context, action: String): Intent =
            Intent(ctx, BoostService::class.java).setAction(action)
    }

    private var engine: BoostEngine? = null
    private lateinit var audioManager: AudioManager
    private var pausedForCall = false
    private var callPollHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        engine = BoostEngine()
        createChannel()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val boost = prefs.getInt(KEY_BOOST, 130).coerceIn(100, 150)
        val voice = prefs.getBoolean(KEY_VOICE, true)
        val on = prefs.getBoolean(KEY_ON, false)

        engine?.setBoost(boost)
        engine?.setVoicePreset(voice)
        engine?.setEnabled(on)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.addOnModeChangedListener(
                    mainExecutor,
                    AudioManager.OnModeChangedListener { refreshCallState() }
                )
            } catch (_: Exception) {}
        } else {
            startCallPolling()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var boost = prefs.getInt(KEY_BOOST, 130).coerceIn(100, 150)
        var voice = prefs.getBoolean(KEY_VOICE, true)
        var on = prefs.getBoolean(KEY_ON, false)

        when (intent?.action) {
            ACTION_START -> { on = true }
            ACTION_STOP -> {
                saveState(boost, voice, false)
                stopAndRelease()
                return START_NOT_STICKY
            }
            ACTION_SET_BOOST -> {
                boost = intent.getIntExtra(EXTRA_BOOST, boost).coerceIn(100, 150)
                on = true
            }
            ACTION_SET_VOICE -> {
                voice = intent.getBooleanExtra(EXTRA_VOICE, voice)
                on = true
            }
            ACTION_INC -> {
                boost = (boost + 10).coerceIn(100, 150)
                on = true
            }
            ACTION_DEC -> {
                boost = (boost - 10).coerceIn(100, 150)
                on = true
            }
            ACTION_TOGGLE -> { on = !on }
        }

        saveState(boost, voice, on)
        engine?.setBoost(boost)
        engine?.setVoicePreset(voice)
        // Si en appel, on ne réactive pas tout de suite.
        if (isInCall()) {
            pausedForCall = on
            engine?.setEnabled(false)
        } else {
            pausedForCall = false
            if (on) ensureSystemMaxVolume()
            engine?.setEnabled(on)
            if (!on) restoreSystemVolume()
        }
        writeDebugPrefs()

        startForegroundWithType(buildNotification(boost, voice, on))
        return START_STICKY
    }

    /** Monte STREAM_MUSIC au max (base saine avant gain logiciel). */
    private fun ensureSystemMaxVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PREV_VOL, -1) < 0) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_PREV_VOL, cur).apply()
            }
            if (cur < max) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        } catch (_: Exception) {}
    }

    private fun restoreSystemVolume() {
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val prev = prefs.getInt(KEY_PREV_VOL, -1)
            if (prev >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
                prefs.edit().putInt(KEY_PREV_VOL, -1).apply()
            }
        } catch (_: Exception) {}
    }

    private fun writeDebugPrefs() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_LAST_GAIN, engine?.appliedGainMb ?: -1)
                .putString(KEY_LAST_ERROR, engine?.error)
                .putString(KEY_EQ, engine?.eqSummary)
                .apply()
        } catch (_: Exception) {}
    }

    private fun saveState(boost: Int, voice: Boolean, on: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_BOOST, boost)
            .putBoolean(KEY_VOICE, voice)
            .putBoolean(KEY_ON, on)
            .apply()
    }

    private fun isInCall(): Boolean {
        return when (audioManager.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }

    private fun refreshCallState() {
        val e = engine ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val wantOn = prefs.getBoolean(KEY_ON, false)
        if (isInCall()) {
            if (wantOn) {
                pausedForCall = true
                e.setEnabled(false)
                writeDebugPrefs()
                updateNotification()
            }
        } else if (pausedForCall && wantOn) {
            pausedForCall = false
            ensureSystemMaxVolume()
            e.setEnabled(true)
            writeDebugPrefs()
            updateNotification()
        }
    }

    private fun startCallPolling() {
        val h = Handler(Looper.getMainLooper())
        callPollHandler = h
        val r = object : Runnable {
            override fun run() {
                refreshCallState()
                h.postDelayed(this, 2000)
            }
        }
        h.postDelayed(r, 2000)
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val n = buildNotification(
            prefs.getInt(KEY_BOOST, 130),
            prefs.getBoolean(KEY_VOICE, true),
            prefs.getBoolean(KEY_ON, false)
        )
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Booster voiture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val i = intent(this, action)
        return PendingIntent.getService(
            this, action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(boost: Int, voice: Boolean, on: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (on) "Booster ON — $boost%" else "Booster OFF"
        val sysVol = try {
            "${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) { "?/?" }
        val text = (if (voice) "Voix podcast" else "Brut") + " • sys $sysVol" +
            (if (pausedForCall) " • pause appel" else "") +
            (engine?.error?.let { " • $it" } ?: "")

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_previous, "-10", actionIntent(ACTION_DEC))
            .addAction(
                android.R.drawable.ic_media_play,
                if (on) "OFF" else "ON",
                actionIntent(ACTION_TOGGLE)
            )
            .addAction(android.R.drawable.ic_media_next, "+10", actionIntent(ACTION_INC))
            .build()
    }

    private fun startForegroundWithType(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            // Dernier recours : notification simple.
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopAndRelease() {
        callPollHandler?.removeCallbacksAndMessages(null)
        try {
            engine?.release()
        } catch (_: Exception) {}
        engine = null
        restoreSystemVolume()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        callPollHandler?.removeCallbacksAndMessages(null)
        try {
            engine?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
