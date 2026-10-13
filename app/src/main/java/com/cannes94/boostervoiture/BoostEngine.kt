package com.cannes94.boostervoiture

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Moteur de boost PAR SESSION audio (usage perso voiture).
 *
 * L'effet global (session 0) est déprécié et ignoré par beaucoup d'appareils.
 * On attache donc un LoudnessEnhancer (+ Equalizer) à la session de chaque lecteur
 * (Spotify, YouTube Music...) que celui-ci annonce par le broadcast
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION (voir BoostService).
 *
 * Total affiché 100..150% : 100 = gain 0mB, 150 = +1000mB (~+10dB).
 * Preset voix v2 : flat partout sauf +3dB sur 1-4kHz, AUCUNE coupe
 * (la coupe des basses faisait baisser le niveau global perçu).
 */
class BoostEngine {

    companion object {
        const val MIN_TOTAL = 100
        const val MAX_TOTAL = 150
        const val MAX_GAIN_MB = 1000
        /** 1% de boost = 20 mB de gain (100..150% => 0..1000 mB). */
        const val MB_PER_PERCENT = 20
        /** Garde-fou : au-delà, on libère la session la plus ancienne. */
        private const val MAX_SESSIONS = 8
        private const val TAG = "BoostEngine"
    }

    private class SessionFx(
        val sessionId: Int,
        val packageName: String?,
        val loudness: LoudnessEnhancer,
        val eq: Equalizer?
    )

    private val sessions = LinkedHashMap<Int, SessionFx>()

    var error: String? = null
        private set

    var totalPercent: Int = 130
        private set
    var voiceEnabled: Boolean = true
        private set
    var masterOn: Boolean = false
        private set
    var appliedGainMb: Int = 0
        private set
    var eqSummary: String = "EQ: -"
        private set

    /** Au moins un lecteur a annoncé sa session audio. */
    val available: Boolean
        @Synchronized get() = sessions.isNotEmpty()

    /** Paquets (ou n° de session si inconnu) des lecteurs auxquels le boost est attaché. */
    val sessionPackages: List<String>
        @Synchronized get() = sessions.values.map { it.packageName ?: "#${it.sessionId}" }

    @Synchronized
    fun attachSession(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return // 0 = global (déprécié), négatif = valeur invalide
        if (sessions.containsKey(sessionId)) return

        val loudness = try {
            LoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "LoudnessEnhancer refusé pour la session $sessionId ($packageName)", e)
            error = "Session $sessionId refusée: ${e.message}"
            return
        }
        val eq = try {
            Equalizer(0, sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer indisponible pour la session $sessionId, boost seul", e)
            null
        }

        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.keys.first()
            sessions.remove(oldest)?.let { releaseFx(it) }
        }
        val fx = SessionFx(sessionId, packageName, loudness, eq)
        sessions[sessionId] = fx
        Log.i(TAG, "Session $sessionId attachée ($packageName)")

        applyGain(fx)
        applyEq(fx)
        applyEnabled(fx)
    }

    @Synchronized
    fun detachSession(sessionId: Int) {
        sessions.remove(sessionId)?.let {
            Log.i(TAG, "Session $sessionId libérée (${it.packageName})")
            releaseFx(it)
        }
    }

    @Synchronized
    fun setBoost(total: Int) {
        totalPercent = total.coerceIn(MIN_TOTAL, MAX_TOTAL)
        appliedGainMb = ((totalPercent - MIN_TOTAL) * MB_PER_PERCENT).coerceIn(0, MAX_GAIN_MB)
        sessions.values.forEach { applyGain(it) }
    }

    @Synchronized
    fun setVoicePreset(enabled: Boolean) {
        voiceEnabled = enabled
        sessions.values.forEach {
            applyEq(it)
            applyEnabled(it)
        }
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        masterOn = on
        if (on) error = null
        sessions.values.forEach { applyEnabled(it) }
    }

    private fun applyEnabled(fx: SessionFx) {
        try {
            fx.loudness.setEnabled(masterOn)
            fx.eq?.enabled = masterOn && voiceEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Activation impossible (session ${fx.sessionId})", e)
            error = "Activation impossible: ${e.message}"
        }
    }

    private fun applyGain(fx: SessionFx) {
        try {
            fx.loudness.setTargetGain(appliedGainMb)
        } catch (e: Exception) {
            Log.e(TAG, "Gain refusé (session ${fx.sessionId})", e)
            error = "Gain refusé par l'appareil: ${e.message}"
        }
    }

    private fun applyEq(fx: SessionFx) {
        val e = fx.eq ?: return
        try {
            if (!voiceEnabled) {
                for (i in 0 until e.numberOfBands) {
                    e.setBandLevel(i.toShort(), 0)
                }
                eqSummary = "EQ: OFF (flat)"
                return
            }
            val range = e.bandLevelRange // [min, max] en mB, ex. -1500..1500
            val applied = StringBuilder()
            for (i in 0 until e.numberOfBands) {
                val band = i.toShort()
                val centerHz = e.getCenterFreq(band) / 1000
                // v2 : jamais de coupe, petit boost voix seulement.
                val target: Int = if (centerHz in 1000..4000) 300 else 0
                e.setBandLevel(band, target.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                applied.append(centerHz).append("Hz=").append(target).append("mB;")
            }
            eqSummary = "EQ: $applied"
        } catch (ex: Exception) {
            // EQ optionnel : on ne bloque pas le boost si l'EQ échoue.
            Log.w(TAG, "EQ en échec (session ${fx.sessionId})", ex)
            eqSummary = "EQ: indisponible"
            try {
                e.enabled = false
            } catch (_: Exception) {}
        }
    }

    private fun releaseFx(fx: SessionFx) {
        try {
            fx.loudness.setEnabled(false)
        } catch (_: Exception) {}
        try {
            fx.eq?.enabled = false
        } catch (_: Exception) {}
        try {
            fx.loudness.release()
        } catch (_: Exception) {}
        try {
            fx.eq?.release()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun release() {
        sessions.values.forEach { releaseFx(it) }
        sessions.clear()
        masterOn = false
    }
}
