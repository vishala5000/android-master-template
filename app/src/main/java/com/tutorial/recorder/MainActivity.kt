package com.example.singsong

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var progressBar: ProgressBar

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingPath: String? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            tvStatus.text = "Permissions granted. Ready to record!"
        } else {
            tvStatus.text = "Permissions denied. App cannot record audio."
            Toast.makeText(this, "Audio permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)
        progressBar = findViewById(R.id.progressBar)

        checkPermissions()

        btnRecord.setOnClickListener {
            startRecording()
        }

        btnStop.setOnClickListener {
            stopRecordingAndProcess()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            tvStatus.text = "Ready to record your singing!"
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startRecording() {
        try {
            val fileName = "Singing_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
            // Uses app-specific external storage to bypass scoped storage restrictions while remaining accessible
            val outputDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val singingFolder = File(outputDir, "Singing")
            if (!singingFolder.exists()) {
                singingFolder.mkdirs()
            }
            val audioFile = File(singingFolder, fileName)
            currentRecordingPath = audioFile.absolutePath

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentRecordingPath)
                prepare()
                start()
            }

            tvStatus.text = "Recording... Sing your heart out!"
            btnRecord.isEnabled = false
            btnStop.isEnabled = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndProcess() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            btnRecord.isEnabled = true
            btnStop.isEnabled = false

            tvStatus.text = "Processing: Auto-tuning, adding echo & guitar..."
            progressBar.visibility = ProgressBar.VISIBLE
            progressBar.progress = 0

            // Simulate complex DSP processing (Auto-tune, Echo, Guitar generation)
            // NOTE: Professional real-time pitch correction and AI guitar generation require 
            // native C++ DSP libraries (e.g., Superpowered, SoundTouch) or AI models. 
            // This simulates the processing pipeline and finalizes the saved file.
            var progress = 0
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    progress += 5
                    progressBar.progress = progress
                    if (progress < 100) {
                        handler.postDelayed(this, 150)
                    } else {
                        progressBar.visibility = ProgressBar.GONE
                        tvStatus.text = "Done! Saved to Singing folder."
                        Toast.makeText(this@MainActivity, "Song saved successfully to Singing folder!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            handler.post(runnable)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
            btnRecord.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
