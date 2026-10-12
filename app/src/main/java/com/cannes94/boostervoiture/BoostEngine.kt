package com.cannes94.boostervoiture

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
/**
 * Moteur de boost global (session audio 0) pour usage perso voiture.
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
        private const val TAG = "BoostEngine"
    }

    private var loudness: LoudnessEnhancer? = null
    private var eq: Equalizer? = null

    var error: String? = null
        private set
    val available: Boolean get() = loudness != null

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

    init {
        try {
            // Session 0 = effet global (dépend de l'OEM, à tester par appareil).
            loudness = LoudnessEnhancer(0)
            eq = try {
                Equalizer(0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Equalizer indisponible, boost seul", e)
                null
            }
            loudness?.setEnabled(false)
            eq?.enabled = false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Effet global non supporté", e)
            error = "Effet global non supporté sur cet appareil: ${e.message}"
            loudness = null
            eq = null
        } catch (e: Exception) {
            Log.e(TAG, "Init audio impossible", e)
            error = "Init audio impossible: ${e.message}"
            loudness = null
            eq = null
        }
    }

    @Synchronized
    fun setBoost(total: Int) {
        totalPercent = total.coerceIn(MIN_TOTAL, MAX_TOTAL)
        applyGain()
    }

    @Synchronized
    fun setVoicePreset(enabled: Boolean) {
        voiceEnabled = enabled
        applyEq()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        masterOn = on
        try {
            if (!available) return
            if (on) {
                applyGain()
                applyEq()
                loudness?.setEnabled(true)
                if (voiceEnabled) eq?.enabled = true
                error = null
            } else {
                loudness?.setEnabled(false)
                eq?.enabled = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Activation impossible", e)
            error = "Activation impossible: ${e.message}"
        }
    }

    private fun applyGain() {
        val l = loudness ?: return
        val gainMb = ((totalPercent - MIN_TOTAL) * MB_PER_PERCENT).coerceIn(0, MAX_GAIN_MB)
        appliedGainMb = gainMb
        try {
            l.setTargetGain(gainMb)
        } catch (e: Exception) {
            Log.e(TAG, "Gain refusé", e)
            error = "Gain refusé par l'appareil: ${e.message}"
        }
    }

    private fun applyEq() {
        val e = eq ?: return
        try {
            if (!voiceEnabled) {
                e.enabled = false
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
            if (masterOn) e.enabled = true
        } catch (ex: Exception) {
            // EQ optionnel : on ne bloque pas le boost si l'EQ échoue.
            Log.w(TAG, "EQ en échec", ex)
            eqSummary = "EQ: indisponible"
            try {
                e.enabled = false
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun release() {
        try {
            loudness?.setEnabled(false)
        } catch (_: Exception) {}
        try {
            eq?.enabled = false
        } catch (_: Exception) {}
        try {
            loudness?.release()
        } catch (_: Exception) {}
        try {
            eq?.release()
        } catch (_: Exception) {}
        loudness = null
        eq = null
        masterOn = false
    }
}
