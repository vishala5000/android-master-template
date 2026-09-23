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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

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
        
        tvStatus.text = "🎧 Mixing professional track..."
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

                if (voiceArray.isEmpty()) {
                    runOnUiThread { tvStatus.text = "❌ No audio recorded."; btnSave.isEnabled = true }
                    return@execute
                }

                val musicArray = generateProfessionalTrack(voiceArray.size, sampleRate)
                val normalizedVoice = normalizeAudio(voiceArray, 0.75f)
                val mixedArray = mixAndMaster(normalizedVoice, musicArray)

                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (!musicDir.exists()) musicDir.mkdirs()
                
                val outFile = File(musicDir, "SingAuto_Perfect_${System.currentTimeMillis()}.wav")
                writeWav(outFile, mixedArray, sampleRate)

                runOnUiThread {
                    tvStatus.text = "✅ Perfect Song Saved!\n${outFile.name}"
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

    private fun normalizeAudio(audio: ShortArray, targetPeak: Float): ShortArray {
        var maxPeak = 0
        for (sample in audio) {
            val absVal = abs(sample.toInt())
            if (absVal > maxPeak) maxPeak = absVal
        }
        if (maxPeak == 0) return audio
        
        val gain = (Short.MAX_VALUE * targetPeak) / maxPeak
        val normalized = ShortArray(audio.size)
        for (i in audio.indices) {
            normalized[i] = (audio[i].toInt() * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return normalized
    }

    private fun generateProfessionalTrack(totalSamples: Int, sr: Int): ShortArray {
        val track = FloatArray(totalSamples)
        val bpm = 120.0
        val beatDuration = 60.0 / bpm
        
        val chords = listOf(
            listOf(130.81, 164.81, 196.00), // C
            listOf(196.00, 246.94, 293.66), // G
            listOf(220.00, 261.63, 329.63), // Am
            listOf(174.61, 220.00, 261.63)  // F
        )
        val beatsPerChord = 4.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sr
            val currentBeat = t / beatDuration
            val chordIndex = (currentBeat / beatsPerChord).toInt() % chords.size
            val beatInChord = currentBeat % beatsPerChord
            
            var padSample = 0.0
            val chord = chords[chordIndex]
            for (freq in chord) {
                padSample += sin(2.0 * PI * freq * t) * 0.5
                padSample += sin(2.0 * PI * freq * 2 * t) * 0.2
                padSample += sin(2.0 * PI * freq * 3 * t) * 0.1
            }
            val padEnv = min(1.0, t / 1.5) 
            padSample *= padEnv * 0.15 

            var bassSample = 0.0
            val bassFreq = chords[chordIndex][0] / 2 
            val timeInBeat = currentBeat % 1.0
            val bassEnv = exp(-timeInBeat * 8.0) 
            bassSample = sin(2.0 * PI * bassFreq * t) * bassEnv * 0.4

            var drumSample = 0.0
            val beatFraction = currentBeat % 1.0
            
            if (currentBeat % 2 < 0.1 || (currentBeat % 2 > 0.9 && currentBeat % 2 < 1.0)) {
                val kickT = (currentBeat % 1.0) * beatDuration
                if (kickT < 0.3) {
                    val kickFreq = 150 * exp(-kickT * 30) + 40
                    val kickEnv = exp(-kickT * 10)
                    drumSample += sin(2.0 * PI * kickFreq * kickT) * kickEnv * 0.6
                }
            }
            
            if ((currentBeat + 1) % 2 < 0.1) {
                val snareT = ((currentBeat + 1) % 1.0) * beatDuration
                if (snareT < 0.2) {
                    val snareEnv = exp(-snareT * 15)
                    drumSample += (Random.nextDouble() * 2 - 1) * snareEnv * 0.3 
                }
            }

            if (beatFraction < 0.05 || (beatFraction > 0.45 && beatFraction < 0.55)) {
                val hatT = (beatFraction % 0.5) * beatDuration
                if (hatT < 0.05) {
                    val hatEnv = exp(-hatT * 80)
                    drumSample += (Random.nextDouble() * 2 - 1) * hatEnv * 0.15
                }
            }

            track[i] = (padSample + bassSample + drumSample).toFloat()
        }
        
        val shortTrack = ShortArray(totalSamples)
        for (i in track.indices) {
            shortTrack[i] = (track[i] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return shortTrack
    }

    private fun mixAndMaster(voice: ShortArray, music: ShortArray): ShortArray {
        val length = minOf(voice.size, music.size)
        val mixed = ShortArray(length)
        val fadeSamples = sampleRate / 20 
        
        for (i in 0 until length) {
            val sum = voice[i].toInt() + music[i].toInt()
            val clipped = when {
                sum > Short.MAX_VALUE -> Short.MAX_VALUE
                sum < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> sum.toShort()
            }
            
            var finalSample = clipped.toInt()
            if (i < fadeSamples) {
                finalSample = (finalSample * (i.toDouble() / fadeSamples)).toInt()
            } else if (i > length - fadeSamples) {
                finalSample = (finalSample * ((length - i).toDouble() / fadeSamples)).toInt()
            }
            
            mixed[i] = finalSample.toShort()
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
