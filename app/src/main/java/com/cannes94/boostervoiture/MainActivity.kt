package com.cannes94.boostervoiture

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * UI manuelle gros boutons pour voiture (utilisée à l'arrêt).
 * Ordre conseillé : 1. régler ici 2. lancer Spotify 3. brancher Android Auto.
 */
class MainActivity : Activity() {

    companion object {
        private const val NOTIF_REQ = 1001
    }

    private lateinit var statusView: TextView
    private lateinit var boostLabel: TextView
    private lateinit var debugView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var seekBar: SeekBar
    private lateinit var voiceSwitch: Switch
    private lateinit var globalSwitch: Switch

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // Le SharedPreferences ne garde qu'une référence faible : on la conserve ici.
    // Le service écrit son état dans les prefs, l'UI se met à jour toute seule
    // (plus besoin des refresh() différés de 300 ms).
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val voiceListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        val i = BoostService.intent(this, BoostService.ACTION_SET_VOICE)
            .putExtra(BoostService.EXTRA_VOICE, checked)
        sendService(i)
    }

    private val globalListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        val i = BoostService.intent(this, BoostService.ACTION_SET_GLOBAL)
            .putExtra(BoostService.EXTRA_GLOBAL, checked)
        sendService(i)
    }

    // SeekBar 0..50 => 100..150 % (fonctionne aussi avant API 26, où min n'existe pas).
    private val seekRange = BoostEngine.MAX_TOTAL - BoostEngine.MIN_TOTAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifPermissionIfNeeded()
        showDisclaimerOnce()

        val root = ScrollView(this).apply { setPadding(32.dp, 32.dp, 32.dp, 32.dp) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(col)

        val title = TextView(this).apply {
            text = getString(R.string.title_booster)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 24.dp, 0, 24.dp)
        }
        toggleBtn = Button(this).apply {
            textSize = 24f
            minimumHeight = 180.dp
        }
        boostLabel = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 32.dp, 0, 8.dp)
        }
        seekBar = SeekBar(this).apply {
            max = seekRange
        }
        voiceSwitch = Switch(this).apply {
            text = getString(R.string.preset_voice)
            textSize = 18f
            setPadding(0, 32.dp, 0, 32.dp)
        }
        globalSwitch = Switch(this).apply {
            text = getString(R.string.global_switch)
            textSize = 18f
            setPadding(0, 0, 0, 24.dp)
        }
        val abRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val minusBtn = Button(this).apply { text = "-10"; textSize = 20f }
        val plusBtn = Button(this).apply { text = "+10"; textSize = 20f }
        abRow.addView(minusBtn)
        abRow.addView(plusBtn)
        val sysMaxBtn = Button(this).apply {
            text = getString(R.string.btn_sys_max)
            textSize = 18f
        }
        debugView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24.dp, 0, 0)
        }
        val stopBtn = Button(this).apply {
            text = getString(R.string.btn_stop_release)
            textSize = 18f
        }
        val help = TextView(this).apply {
            text = getString(R.string.help_protocol)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 32.dp, 0, 0)
        }

        toggleBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_TOGGLE))
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) boostLabel.text = getString(R.string.boost_total, BoostEngine.MIN_TOTAL + p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val p = BoostEngine.MIN_TOTAL + (s?.progress ?: 0).coerceIn(0, seekRange)
                val i = BoostService.intent(this@MainActivity, BoostService.ACTION_SET_BOOST)
                    .putExtra(BoostService.EXTRA_BOOST, p)
                sendService(i)
            }
        })
        stopBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_STOP))
        }
        minusBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_DEC))
        }
        plusBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_INC))
        }
        sysMaxBtn.setOnClickListener {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_sys_impossible, e.message), Toast.LENGTH_LONG).show()
            }
            refresh()
        }

        col.addView(title)
        col.addView(statusView)
        col.addView(toggleBtn)
        col.addView(boostLabel)
        col.addView(seekBar)
        col.addView(voiceSwitch)
        col.addView(globalSwitch)
        col.addView(abRow)
        col.addView(sysMaxBtn)
        col.addView(debugView)
        col.addView(stopBtn)
        col.addView(help)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences(BoostService.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    override fun onPause() {
        getSharedPreferences(BoostService.PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun refresh() {
        val prefs = getSharedPreferences(BoostService.PREFS, MODE_PRIVATE)
        val boost = prefs.getInt(BoostService.KEY_BOOST, 130)
            .coerceIn(BoostEngine.MIN_TOTAL, BoostEngine.MAX_TOTAL)
        val voice = prefs.getBoolean(BoostService.KEY_VOICE, true)
        val global = prefs.getBoolean(BoostService.KEY_GLOBAL, false)
        val on = prefs.getBoolean(BoostService.KEY_ON, false)

        // Évite la boucle du listener quand on restaure l'état.
        voiceSwitch.setOnCheckedChangeListener(null)
        voiceSwitch.isChecked = voice
        voiceSwitch.setOnCheckedChangeListener(voiceListener)

        globalSwitch.setOnCheckedChangeListener(null)
        globalSwitch.isChecked = global
        globalSwitch.setOnCheckedChangeListener(globalListener)

        seekBar.progress = boost - BoostEngine.MIN_TOTAL
        boostLabel.text = getString(R.string.boost_total, boost)
        statusView.text = if (on) getString(R.string.state_on, boost) else getString(R.string.state_off)
        toggleBtn.text = if (on) getString(R.string.btn_stop) else getString(R.string.btn_start)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sysCur = try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
        val sysMax = try { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
        val gain = prefs.getInt(BoostService.KEY_LAST_GAIN, -1)
        val err = prefs.getString(BoostService.KEY_LAST_ERROR, null)
        val eq = prefs.getString(BoostService.KEY_EQ, null)
        val sessions = prefs.getString(BoostService.KEY_SESSIONS, null)
        val gainTxt = if (gain >= 0) "+${gain}mB" else getString(R.string.debug_gain_na)
        val globalTxt = getString(
            if (global) R.string.debug_global_on else R.string.debug_global_off
        )
        debugView.text = getString(R.string.debug_sys_gain, sysCur, sysMax, gainTxt) +
            (if (err != null) "\n" + getString(R.string.debug_effect_error, err)
            else "\n" + getString(R.string.debug_effect_ok)) +
            (if (eq != null) "\n$eq" else "") +
            "\n" + (if (sessions.isNullOrEmpty()) getString(R.string.debug_sessions_none)
            else getString(R.string.debug_sessions, sessions)) +
            "\n" + getString(R.string.debug_global, globalTxt, gainTxt)
    }

    private fun sendService(i: Intent) {
        try {
            if (i.action == BoostService.ACTION_STOP) {
                startService(i)
                return
            }
            startForegroundService(i)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_service_impossible, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_REQ &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, getString(R.string.notif_perm_denied), Toast.LENGTH_LONG).show()
        }
    }

    private fun showDisclaimerOnce() {
        val p = getSharedPreferences("app", MODE_PRIVATE)
        if (p.getBoolean("disclaimer_seen", false)) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.disclaimer_title))
            .setMessage(getString(R.string.disclaimer_msg))
            .setPositiveButton(getString(R.string.disclaimer_ok)) { _, _ ->
                p.edit().putBoolean("disclaimer_seen", true).apply()
            }
            .setCancelable(false)
            .show()
    }
}
