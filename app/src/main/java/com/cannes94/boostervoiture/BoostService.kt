package com.cannes94.boostervoiture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

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

        const val ACTION_START = "com.cannes94.boostervoiture.START"
        const val ACTION_STOP = "com.cannes94.boostervoiture.STOP"
        const val ACTION_SET_BOOST = "com.cannes94.boostervoiture.SET_BOOST"
        const val ACTION_SET_VOICE = "com.cannes94.boostervoiture.SET_VOICE"
        const val ACTION_INC = "com.cannes94.boostervoiture.INC"
        const val ACTION_DEC = "com.cannes94.boostervoiture.DEC"
        const val ACTION_TOGGLE = "com.cannes94.boostervoiture.TOGGLE"
        const val ACTION_SET_GLOBAL = "com.cannes94.boostervoiture.SET_GLOBAL"

        const val EXTRA_BOOST = "boost"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_GLOBAL = "global"
        const val KEY_BOOST = "boost"
        const val KEY_VOICE = "voice"
        const val KEY_GLOBAL = "global"
        const val KEY_ON = "on"
        const val KEY_PREV_VOL = "prev_vol"
        const val KEY_LAST_GAIN = "last_gain_mb"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_EQ = "last_eq"
        const val KEY_SESSIONS = "last_sessions"
        private const val TAG = "BoostService"

        fun intent(ctx: Context, action: String): Intent =
            Intent(ctx, BoostService::class.java).setAction(action)
    }

    private var engine: BoostEngine? = null
    private lateinit var audioManager: AudioManager
    private var pausedForCall = false
    private var callPollHandler: Handler? = null
    // Gardé pour pouvoir le désinscrire dans onDestroy (sinon fuite du service).
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /**
     * Les lecteurs (Spotify, YouTube Music...) annoncent leur session audio par ce broadcast.
     * C'est la seule façon supportée d'attacher un effet à leur son (la session 0 est dépréciée).
     */
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            if (pkg == packageName) return
            val errorBefore = engine?.error
            val changed = when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    engine?.attachSession(session, pkg) ?: false
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                    engine?.detachSession(session) ?: false
                else -> false
            }
            // Les annonces répétées (heartbeat NewPipe) ne doivent ni réécrire
            // les prefs ni reconstruire la notification. Mais un changement
            // d'erreur (session refusée) doit rester visible dans le debug.
            if (SessionEvents.shouldWriteDebug(changed, errorBefore, engine?.error)) {
                writeDebugPrefs()
            }
            if (changed) {
                updateNotification()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSessionReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try {
            // Émetteurs = autres applis => le récepteur doit être exporté.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(sessionReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Récepteur de sessions audio impossible", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        engine = BoostEngine()
        createChannel()

        val boost = prefs.getInt(KEY_BOOST, 130).coerceIn(BoostEngine.MIN_TOTAL, BoostEngine.MAX_TOTAL)
        val voice = prefs.getBoolean(KEY_VOICE, true)
        val global = prefs.getBoolean(KEY_GLOBAL, false)
        val on = prefs.getBoolean(KEY_ON, false)

        engine?.setBoost(boost)
        engine?.setVoicePreset(voice)
        engine?.setGlobalEnabled(global)
        engine?.setEnabled(on)
        registerSessionReceiver()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val l = AudioManager.OnModeChangedListener { refreshCallState() }
                audioManager.addOnModeChangedListener(mainExecutor, l)
                modeListener = l
            } catch (e: Exception) {
                Log.w(TAG, "Listener de mode audio indisponible, polling", e)
                startCallPolling()
            }
        } else {
            startCallPolling()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var boost = prefs.getInt(KEY_BOOST, 130).coerceIn(BoostEngine.MIN_TOTAL, BoostEngine.MAX_TOTAL)
        var voice = prefs.getBoolean(KEY_VOICE, true)
        var global = prefs.getBoolean(KEY_GLOBAL, false)
        var on = prefs.getBoolean(KEY_ON, false)

        when (intent?.action) {
            ACTION_START -> { on = true }
            ACTION_STOP -> {
                saveState(boost, voice, global, false)
                stopAndRelease()
                return START_NOT_STICKY
            }
            ACTION_SET_BOOST -> {
                boost = intent.getIntExtra(EXTRA_BOOST, boost).coerceIn(BoostEngine.MIN_TOTAL, BoostEngine.MAX_TOTAL)
                on = true
            }
            ACTION_SET_VOICE -> {
                voice = intent.getBooleanExtra(EXTRA_VOICE, voice)
                on = true
            }
            ACTION_SET_GLOBAL -> {
                global = intent.getBooleanExtra(EXTRA_GLOBAL, global)
                on = true
            }
            ACTION_INC -> {
                boost = (boost + 10).coerceIn(BoostEngine.MIN_TOTAL, BoostEngine.MAX_TOTAL)
                on = true
            }
            ACTION_DEC -> {
                boost = (boost - 10).coerceIn(BoostEngine.MIN_TOTAL, BoostEngine.MAX_TOTAL)
                on = true
            }
            ACTION_TOGGLE -> { on = !on }
        }

        saveState(boost, voice, global, on)
        engine?.setBoost(boost)
        engine?.setVoicePreset(voice)
        engine?.setGlobalEnabled(global)
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
            if (prefs.getInt(KEY_PREV_VOL, -1) < 0) {
                prefs.edit()
                    .putInt(KEY_PREV_VOL, cur).apply()
            }
            if (cur < max) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        } catch (e: Exception) { Log.w(TAG, "Exception ignorée", e) }
    }

    private fun restoreSystemVolume() {
        try {
            val prev = prefs.getInt(KEY_PREV_VOL, -1)
            if (prev >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
                prefs.edit().putInt(KEY_PREV_VOL, -1).apply()
            }
        } catch (e: Exception) { Log.w(TAG, "Exception ignorée", e) }
    }

    private fun writeDebugPrefs() {
        try {
            prefs.edit()
                .putInt(KEY_LAST_GAIN, engine?.appliedGainMb ?: -1)
                .putString(KEY_LAST_ERROR, engine?.error)
                .putString(KEY_EQ, engine?.eqSummary)
                .putString(KEY_SESSIONS, engine?.sessionPackages?.joinToString(", ")?.ifEmpty { null })
                .apply()
        } catch (e: Exception) { Log.w(TAG, "Exception ignorée", e) }
    }

    private fun saveState(boost: Int, voice: Boolean, global: Boolean, on: Boolean) {
        prefs.edit()
            .putInt(KEY_BOOST, boost)
            .putBoolean(KEY_VOICE, voice)
            .putBoolean(KEY_GLOBAL, global)
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
        val n = buildNotification(
            prefs.getInt(KEY_BOOST, 130),
            prefs.getBoolean(KEY_VOICE, true),
            prefs.getBoolean(KEY_ON, false)
        )
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) { Log.w(TAG, "Exception ignorée", e) }
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
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
        val title = if (on) getString(R.string.notif_title_on, boost) else getString(R.string.notif_title_off)
        val sysVol = try {
            "${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) { "?/?" }
        val text = (if (voice) getString(R.string.notif_mode_voice) else getString(R.string.notif_mode_raw)) +
            getString(R.string.notif_sys, sysVol) +
            (if (pausedForCall) getString(R.string.notif_paused) else "") +
            (engine?.error?.let { " • $it" } ?: "")

        return Notification.Builder(this, CHANNEL_ID)
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
        // Manifeste : permission FOREGROUND_SERVICE_MEDIA_PLAYBACK +
        // foregroundServiceType="mediaPlayback" déjà déclarés, un seul appel suffit.
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
        } catch (e: Exception) {
            Log.e(TAG, "startForeground impossible, arrêt", e)
            stopSelf()
        }
    }

    private fun stopAndRelease() {
        callPollHandler?.removeCallbacksAndMessages(null)
        try {
            engine?.release()
        } catch (e: Exception) { Log.w(TAG, "Exception ignorée", e) }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let {
                try {
                    audioManager.removeOnModeChangedListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Désinscription du listener impossible", e)
                }
            }
        }
        modeListener = null
        try {
            unregisterReceiver(sessionReceiver)
        } catch (e: Exception) { Log.w(TAG, "Récepteur déjà désinscrit", e) }
        try {
            engine?.release()
        } catch (e: Exception) { Log.w(TAG, "Exception ignorée", e) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
