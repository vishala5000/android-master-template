package com.example.singauto

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var btnSave: MaterialButton

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var tempVoiceFile: File? = null
    
    private val sampleRate = 44100
    private val executor = Executors.newSingleThreadExecutor()

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            tvStatus.text = "Permissions granted! Tap the mic to sing."
        } else {
            tvStatus.text = "Permissions denied. Cannot record."
            Toast.makeText(this, "Please grant mic and storage permissions", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        fabRecord = findViewById(R.id.fabRecord)
        btnSave = findViewById(R.id.btnSave)

        checkAndRequestPermissions()

        fabRecord.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        btnSave.setOnClickListener {
            processAndSaveAudio()
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions()
            return
        }

        isRecording = true
        btnSave.isEnabled = false
        tvStatus.text = "🎤 Recording... Tap mic to stop!"
        fabRecord.setImageResource(android.R.drawable.ic_media_pause)

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        tempVoiceFile = File(cacheDir, "voice_temp.raw")
        val fos = FileOutputStream(tempVoiceFile)
        val buffer = ByteArray(bufferSize)

        audioRecord?.startRecording()
        
        recordingThread = Thread {
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                if (read > 0) {
                    fos.write(buffer, 0, read)
                }
            }
            fos.close()
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        tvStatus.text = "Processing audio & adding music..."
        fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        btnSave.isEnabled = true
    }

    private fun processAndSaveAudio() {
        btnSave.isEnabled = false
        executor.execute {
            try {
                val voiceData = tempVoiceFile?.readBytes() ?: return@execute
                val voiceShorts = ByteBuffer.wrap(voiceData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val voiceArray = ShortArray(voiceShorts.limit())
                voiceShorts.get(voiceArray)

                // Generate a 30-second background synth pad (C Major Chord)
                val musicArray = generateSynthPad(30, sampleRate)

                // Mix voice and music
                val mixedArray = mixAudio(voiceArray, musicArray)

                // Save to Music folder
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (!musicDir.exists()) musicDir.mkdirs()
                
                val outFile = File(musicDir, "SingAuto_${System.currentTimeMillis()}.wav")
                writeWav(outFile, mixedArray, sampleRate)

                runOnUiThread {
                    tvStatus.text = "✅ Saved to Music folder!\n${outFile.name}"
                    btnSave.isEnabled = true
                    Toast.makeText(this, "Saved to Music folder", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "❌ Error saving file."
                    btnSave.isEnabled = true
                }
            }
        }
    }

    private fun generateSynthPad(durationSec: Int, sampleRate: Int): ShortArray {
        val totalSamples = durationSec * sampleRate
        val samples = ShortArray(totalSamples)
        val freqs = doubleArrayOf(261.63, 329.63, 392.00) // C, E, G
        
        for (i in 0 until totalSamples) {
            var sample = 0.0
            for (f in freqs) {
                sample += Math.sin(2.0 * Math.PI * f * i / sampleRate)
            }
            sample /= freqs.size 
            samples[i] = (sample * Short.MAX_VALUE * 0.4).toInt().toShort() // 40% volume
        }
        return samples
    }

    private fun mixAudio(voice: ShortArray, music: ShortArray): ShortArray {
        val length = minOf(voice.size, music.size)
        val mixed = ShortArray(length)
        for (i in 0 until length) {
            val sum = voice[i].toInt() + music[i].toInt()
            mixed[i] = when {
                sum > Short.MAX_VALUE -> Short.MAX_VALUE
                sum < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> sum.toShort()
            }
        }
        return mixed
    }

    private fun writeWav(file: File, data: ShortArray, sampleRate: Int) {
        val dataSize = data.size * 2
        val fileSize = dataSize + 36
        val header = ByteArray(44)
        
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        writeInt(header, 4, fileSize)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        writeInt(header, 16, 16) 
        writeShort(header, 20, 1.toShort()) 
        writeShort(header, 22, 1.toShort()) 
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, sampleRate * 2) 
        writeShort(header, 32, 2.toShort()) 
        writeShort(header, 34, 16.toShort()) 
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        writeInt(header, 40, dataSize)

        FileOutputStream(file).use { out ->
            out.write(header)
            val buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in data) buffer.putShort(sample)
            out.write(buffer.array())
        }
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value >> 8) and 0xff).toByte()
        buffer[offset + 2] = ((value >> 16) and 0xff).toByte()
        buffer[offset + 3] = ((value >> 24) and 0xff).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value.toInt() and 0xff).toByte()
        buffer[offset + 1] = ((value.toInt() >> 8) and 0xff).toByte()
    }
}
