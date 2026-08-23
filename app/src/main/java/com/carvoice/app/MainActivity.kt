package com.carvoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var logText: TextView

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            perms.add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        logText = findViewById(R.id.logText)

        VoiceService.logCallback = { msg ->
            runOnUiThread {
                logText.append("$msg\n")
            }
        }
        VoiceService.nowPlayingCallback = { title ->
            runOnUiThread {
                nowPlayingText.text = "Now: $title"
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (hasAllPermissions()) {
                startVoiceService()
            } else {
                ActivityCompat.requestPermissions(this, requiredPermissions, 100)
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, VoiceService::class.java))
            statusText.text = "Stopped."
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAllPermissions()) {
            startVoiceService()
        } else {
            statusText.text = "Mic + storage + notification permissions are needed to run."
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        val wakeList = CommandParser.wakePhrases().joinToString(" / ")
        statusText.text = "Listening for: $wakeList — followed by play / pause / next / " +
                "previous / play <song> / skip <N> seconds / trim <front> <back> / a 1-5 rating"
    }
}
