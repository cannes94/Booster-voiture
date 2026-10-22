package com.cannes94.boostervoiture

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Moteur de boost audio (usage perso voiture).
 *
 * Deux moteurs :
 *  - PAR SESSION : un LoudnessEnhancer (+ Equalizer) attaché à la session de chaque
 *    lecteur (Spotify, NewPipe...) qui l'annonce par le broadcast
 *    ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION (voir BoostService). C'est la voie
 *    fiable : chaque lecteur garde son propre volume.
 *  - GLOBAL session 0 : LoudnessEnhancer(0) / Equalizer(0,0), OFF par défaut.
 *    Certains appareils l'ignorent (déprécié), d'autres l'appliquent encore au
 *    mix de sortie — utile pour les applis qui n'annoncent aucune session
 *    (YouTube natif). Sécurité : actif seulement si masterOn ET globalEnabled,
 *    donc jamais pendant un appel (BoostService coupe masterOn).
 *
 * Total affiché 100..150% : 100 = gain 0mB, 150 = +1000mB (~+10dB).
 * Preset voix v2 : flat partout sauf +3dB sur 1-4kHz, AUCUNE coupe
 * (la coupe des basses faisait baisser le niveau global perçu).
 */
class BoostEngine {

    companion object {
        const val MIN_TOTAL = BoostMath.MIN_TOTAL
        const val MAX_TOTAL = BoostMath.MAX_TOTAL
        const val MAX_GAIN_MB = BoostMath.MAX_GAIN_MB
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

    /** Ordre/plafond des sessions ; les effets eux-mêmes vivent dans [sessions]. */
    private var registry = SessionRegistry(MAX_SESSIONS)
    private val sessions = HashMap<Int, SessionFx>()

    private var globalLoudness: LoudnessEnhancer? = null
    private var globalEq: Equalizer? = null

    var error: String? = null
        private set

    var totalPercent: Int = 130
        private set
    var voiceEnabled: Boolean = true
        private set
    var masterOn: Boolean = false
        private set
    var globalEnabled: Boolean = false
        private set
    var appliedGainMb: Int = 0
        private set
    var eqSummary: String = "EQ: -"
        private set

    /** Au moins un lecteur a annoncé sa session audio. */
    val available: Boolean
        @Synchronized get() = registry.size() > 0

    /** Paquets (ou n° de session si inconnu) des lecteurs auxquels le boost est attaché. */
    val sessionPackages: List<String>
        @Synchronized get() = registry.packages()

    init {
        // Session 0 : peut être refusée (appareil, politique audio). On ne bloque pas.
        try {
            globalLoudness = LoudnessEnhancer(0)
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer global refusé (session 0)", e)
        }
        try {
            globalEq = Equalizer(0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer global indisponible (session 0)", e)
        }
        applyGainGlobal()
        applyEqGlobal()
        applyGlobalEnabled()
    }

    @Synchronized
    fun attachSession(sessionId: Int, packageName: String?): Boolean {
        if (sessionId <= 0) return false // 0 = global (voir globalFx), négatif = invalide
        if (registry.has(sessionId)) return false

        val loudness = try {
            LoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "LoudnessEnhancer refusé pour la session $sessionId ($packageName)", e)
            error = "Session $sessionId refusée: ${e.message}"
            return false
        }
        val eq = try {
            Equalizer(0, sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer indisponible pour la session $sessionId, boost seul", e)
            null
        }

        registry.add(sessionId, packageName).forEach { evicted ->
            sessions.remove(evicted)?.let { releaseFx(it) }
        }
        val fx = SessionFx(sessionId, packageName, loudness, eq)
        sessions[sessionId] = fx
        Log.i(TAG, "Session $sessionId attachée ($packageName)")

        applyGain(fx)
        applyEq(fx)
        applyEnabled(fx)
        return true
    }

    @Synchronized
    fun detachSession(sessionId: Int): Boolean {
        val changed = registry.remove(sessionId)
        sessions.remove(sessionId)?.let {
            Log.i(TAG, "Session $sessionId libérée (${it.packageName})")
            releaseFx(it)
        }
        return changed
    }

    @Synchronized
    fun setBoost(total: Int) {
        totalPercent = total.coerceIn(MIN_TOTAL, MAX_TOTAL)
        appliedGainMb = BoostMath.gainMb(totalPercent)
        sessions.values.forEach { applyGain(it) }
        applyGainGlobal()
    }

    @Synchronized
    fun setVoicePreset(enabled: Boolean) {
        voiceEnabled = enabled
        sessions.values.forEach {
            applyEq(it)
            applyEnabled(it)
        }
        applyEqGlobal()
        applyGlobalEnabled()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        masterOn = on
        if (on) error = null
        sessions.values.forEach { applyEnabled(it) }
        applyGlobalEnabled()
    }

    /** Active/désactive le moteur global ; la sécurité masterOn/appel reste prioritaire. */
    @Synchronized
    fun setGlobalEnabled(on: Boolean) {
        globalEnabled = on
        applyGlobalEnabled()
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

    private fun applyGlobalEnabled() {
        val active = BoostMath.globalActive(masterOn, globalEnabled)
        try {
            globalLoudness?.setEnabled(active)
        } catch (e: Exception) {
            Log.e(TAG, "Activation du moteur global impossible", e)
            error = "Global impossible: ${e.message}"
        }
        try {
            globalEq?.enabled = active && voiceEnabled
        } catch (_: Exception) {}
    }

    private fun applyGain(fx: SessionFx) {
        try {
            fx.loudness.setTargetGain(appliedGainMb)
        } catch (e: Exception) {
            Log.e(TAG, "Gain refusé (session ${fx.sessionId})", e)
            error = "Gain refusé par l'appareil: ${e.message}"
        }
    }

    private fun applyGainGlobal() {
        try {
            globalLoudness?.setTargetGain(appliedGainMb)
        } catch (e: Exception) {
            Log.w(TAG, "Gain global refusé", e)
        }
    }

    private fun applyEq(fx: SessionFx) {
        applyEq(fx.eq)
    }

    private fun applyEqGlobal() {
        applyEq(globalEq)
    }

    private fun applyEq(e: Equalizer?) {
        if (e == null) return
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
                val target = BoostMath.voiceTargetMb(centerHz)
                e.setBandLevel(band, target.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                applied.append(centerHz).append("Hz=").append(target).append("mB;")
            }
            eqSummary = "EQ: $applied"
        } catch (ex: Exception) {
            // EQ optionnel : on ne bloque pas le boost si l'EQ échoue.
            Log.w(TAG, "EQ en échec", ex)
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
        registry = SessionRegistry(MAX_SESSIONS)
        try {
            globalLoudness?.setEnabled(false)
        } catch (_: Exception) {}
        try {
            globalEq?.enabled = false
        } catch (_: Exception) {}
        try {
            globalLoudness?.release()
        } catch (_: Exception) {}
        try {
            globalEq?.release()
        } catch (_: Exception) {}
        globalLoudness = null
        globalEq = null
        masterOn = false
        globalEnabled = false
    }
}
