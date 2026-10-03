package com.example.vrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.vrecorder.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaRecorder: MediaRecorder? = null
    private var tempAudioFile: File? = null
    private var isRecording = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission is required to record audio", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recordButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.stopButton.setOnClickListener {
            stopRecording()
        }

        binding.saveButton.setOnClickListener {
            saveRecording()
        }
    }

    private fun startRecording() {
        try {
            tempAudioFile = File.createTempFile("vrecorder_temp", ".m4a", cacheDir)
            
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48000)
                setAudioEncodingBitRate(256000)
                setOutputFile(tempAudioFile!!.absolutePath)
                prepare()
                start()
            }
            
            isRecording = true
            updateUIState(State.RECORDING)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            updateUIState(State.STOPPED)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun saveRecording() {
        val file = tempAudioFile ?: return
        
        try {
            val recordingsDir = File(getExternalFilesDir(Environment.DIRECTORY_RECORDINGS), "VRecorder")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val finalFile = File(recordingsDir, "VRecorder_$timestamp.m4a")

            file.inputStream().use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            file.delete()
            tempAudioFile = null

            binding.savedPathTextView.text = "Saved to: ${finalFile.absolutePath}"
            Toast.makeText(this, "High-quality audio saved successfully!", Toast.LENGTH_LONG).show()
            updateUIState(State.READY)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save recording: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private enum class State { READY, RECORDING, STOPPED }

    private fun updateUIState(state: State) {
        when (state) {
            State.READY -> {
                binding.statusTextView.text = "Status: Ready"
                binding.recordButton.isEnabled = true
                binding.stopButton.isEnabled = false
                binding.saveButton.isEnabled = false
            }
            State.RECORDING -> {
                binding.statusTextView.text = "Status: Recording... Read your story now."
                binding.recordButton.isEnabled = false
                binding.stopButton.isEnabled = true
                binding.saveButton.isEnabled = false
            }
            State.STOPPED -> {
                binding.statusTextView.text = "Status: Recording finished. Ready to save."
                binding.recordButton.isEnabled = true
                binding.stopButton.isEnabled = false
                binding.saveButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaRecorder = null
        tempAudioFile?.delete()
    }
}
