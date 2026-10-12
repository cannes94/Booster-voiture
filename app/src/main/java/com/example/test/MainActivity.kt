package com.example.test

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
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

    private lateinit var statusView: TextView
    private lateinit var boostLabel: TextView
    private lateinit var debugView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var seekBar: SeekBar
    private lateinit var voiceSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifPermissionIfNeeded()
        showDisclaimerOnce()

        val root = ScrollView(this).apply { setPadding(32, 32, 32, 32) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(col)

        val title = TextView(this).apply {
            text = "Booster Voiture"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        toggleBtn = Button(this).apply {
            textSize = 24f
            minimumHeight = 180
        }
        boostLabel = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 8)
        }
        seekBar = SeekBar(this).apply {
            max = 150
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) min = 100
        }
        voiceSwitch = Switch(this).apply {
            text = "Preset Voix podcast (+3dB 1-4kHz, sans coupe)"
            textSize = 18f
            setPadding(0, 32, 0, 32)
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
            text = "Volume système au MAX (test)"
            textSize = 18f
        }
        debugView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        val stopBtn = Button(this).apply {
            text = "Arrêter et libérer"
            textSize = 18f
        }
        val help = TextView(this).apply {
            text = "Protocole A/B : volume voiture fixe.\n" +
                "1. OFF (volume sys seul)\n2. ON brut 130% (voix OFF)\n" +
                "3. ON voix 130% puis 150%.\nSi 2 = 1, l'effet session 0 est ignoré par l'autoradio.\n" +
                "Coupure auto en appel. Max 150% pour limiter la distorsion."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        toggleBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_TOGGLE))
            col.postDelayed({ refresh() }, 300)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) boostLabel.text = "Volume total : ${p.coerceIn(100, 150)}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val p = (s?.progress ?: 130).coerceIn(100, 150)
                val i = BoostService.intent(this@MainActivity, BoostService.ACTION_SET_BOOST)
                    .putExtra(BoostService.EXTRA_BOOST, p)
                sendService(i)
                col.postDelayed({ refresh() }, 300)
            }
        })
        voiceSwitch.setOnCheckedChangeListener { _, checked ->
            val i = BoostService.intent(this, BoostService.ACTION_SET_VOICE)
                .putExtra(BoostService.EXTRA_VOICE, checked)
            sendService(i)
            col.postDelayed({ refresh() }, 300)
        }
        stopBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_STOP))
            col.postDelayed({ refresh() }, 300)
        }
        minusBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_DEC))
            col.postDelayed({ refresh() }, 300)
        }
        plusBtn.setOnClickListener {
            sendService(BoostService.intent(this, BoostService.ACTION_INC))
            col.postDelayed({ refresh() }, 300)
        }
        sysMaxBtn.setOnClickListener {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Volume sys impossible: ${e.message}", Toast.LENGTH_LONG).show()
            }
            col.postDelayed({ refresh() }, 300)
        }

        col.addView(title)
        col.addView(statusView)
        col.addView(toggleBtn)
        col.addView(boostLabel)
        col.addView(seekBar)
        col.addView(voiceSwitch)
        col.addView(abRow)
        col.addView(sysMaxBtn)
        col.addView(debugView)
        col.addView(stopBtn)
        col.addView(help)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val prefs = getSharedPreferences(BoostService.PREFS, MODE_PRIVATE)
        val boost = prefs.getInt(BoostService.KEY_BOOST, 130).coerceIn(100, 150)
        val voice = prefs.getBoolean(BoostService.KEY_VOICE, true)
        val on = prefs.getBoolean(BoostService.KEY_ON, false)

        // Évite la boucle du listener quand on restaure l'état.
        voiceSwitch.setOnCheckedChangeListener(null)
        voiceSwitch.isChecked = voice
        voiceSwitch.setOnCheckedChangeListener { _, checked ->
            val i = BoostService.intent(this, BoostService.ACTION_SET_VOICE)
                .putExtra(BoostService.EXTRA_VOICE, checked)
            sendService(i)
            colRefreshDelay()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) seekBar.min = 100
        seekBar.max = 150
        seekBar.progress = boost
        boostLabel.text = "Volume total : $boost%"
        statusView.text = if (on) "État : ON à $boost%" else "État : OFF"
        toggleBtn.text = if (on) "COUPER (test B)" else "ACTIVER (test B)"

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sysCur = try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
        val sysMax = try { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
        val gain = prefs.getInt(BoostService.KEY_LAST_GAIN, -1)
        val err = prefs.getString(BoostService.KEY_LAST_ERROR, null)
        val eq = prefs.getString(BoostService.KEY_EQ, null)
        debugView.text = "Debug — sys: $sysCur/$sysMax • gain: " +
            (if (gain >= 0) "+${gain}mB" else "n/a") +
            (if (err != null) "\nErreur: $err" else "\nEffet: OK (ou non testé)") +
            (if (eq != null) "\n$eq" else "")
    }

    private fun colRefreshDelay() {
        statusView.postDelayed({ refresh() }, 300)
    }

    private fun sendService(i: Intent) {
        try {
            if (i.action == BoostService.ACTION_STOP) {
                startService(i)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                @Suppress("DEPRECATION")
                startService(i)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Service impossible: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun showDisclaimerOnce() {
        val p = getSharedPreferences("app", MODE_PRIVATE)
        if (p.getBoolean("disclaimer_seen", false)) return
        AlertDialog.Builder(this)
            .setTitle("Volume élevé")
            .setMessage(
                "Un boost prolongé à 150% peut fatiguer l'audition et saturer " +
                    "les petits haut-parleurs. Monte progressivement, " +
                    "baisse si ça grésille. Usage à tes risques."
            )
            .setPositiveButton("Compris") { _, _ ->
                p.edit().putBoolean("disclaimer_seen", true).apply()
            }
            .setCancelable(false)
            .show()
    }
}
