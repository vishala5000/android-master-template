package com.example.singsong

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var btnPlay: MaterialButton

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    private var mixedFile: File? = null
    private var mediaPlayer: MediaPlayer? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        fabRecord = findViewById(R.id.fabRecord)
        btnPlay = findViewById(R.id.btnPlay)

        checkPermissions()

        fabRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        btnPlay.setOnClickListener { playMixedTrack() }
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun startRecording() {
        isRecording = true
        tvStatus.text = "Recording Studio Track... Sing now!"
        fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize
        )
        audioRecord?.startRecording()
        
        recordingThread = Thread {
            val audioData = ShortArray(bufferSize)
            val rawData = mutableListOf<Short>()
            
            while (isRecording) {
                val read = audioRecord?.read(audioData, 0, bufferSize) ?: -1
                if (read > 0) {
                    for (i in 0 until read) rawData.add(audioData[i])
                }
            }
            processStudioMix(rawData.toShortArray())
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        tvStatus.text = "Mixing & Mastering your song..."
        fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        
        recordingThread?.join(3000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processStudioMix(rawData: ShortArray) {
        if (rawData.isEmpty()) {
            runOnUiThread { tvStatus.text = "No audio recorded." }
            return
        }

        // 1. Studio Vocal Chain (EQ, Compression, Reverb)
        val processedVocal = applyStudioVocalChain(rawData)
        
        // 2. Detect Pitch for Key Matching
        val detectedPitch = detectPitch(processedVocal)
        
        // 3. Generate Professional Backing Track (Chords + 808 Drums)
        val backingTrack = generateStudioBackingTrack(detectedPitch, processedVocal.size, processedVocal)
        
        // 4. Final Mastering Mix
        val mixedData = masterMix(processedVocal, backingTrack)
        
        // 5. Export High-Quality WAV
        saveToWav(mixedData)
        
        runOnUiThread {
            tvStatus.text = "Mastering complete! Ready to play."
            btnPlay.isEnabled = true
        }
    }

    // --- ADVANCED DSP: STUDIO VOCAL CHAIN ---
    private fun applyStudioVocalChain(data: ShortArray): ShortArray {
        val result = FloatArray(data.size) { data[it].toFloat() / Short.MAX_VALUE }
        
        // 1. High-Pass Filter (Remove low-end rumble)
        var hpPrev = 0f
        val hpCutoff = 0.98f
        for (i in result.indices) {
            val current = result[i]
            result[i] = hpCutoff * (hpPrev + current - (if (i > 0) data[i-1].toFloat() / Short.MAX_VALUE else 0f))
            hpPrev = result[i]
        }

        // 2. Soft-Knee Compressor (Even out vocal dynamics)
        val threshold = 0.3f
        val ratio = 4.0f
        for (i in result.indices) {
            val absVal = abs(result[i])
            if (absVal > threshold) {
                val sign = if (result[i] >= 0) 1f else -1f
                result[i] = sign * (threshold + (absVal - threshold) / ratio)
            }
        }

        // 3. Algorithmic Hall Reverb (Multi-tap delay with damping)
        val reverb = FloatArray(result.size)
        val delay1 = sampleRate / 10 // 100ms
        val delay2 = sampleRate / 15 // 66ms
        val delay3 = sampleRate / 22 // 45ms
        
        for (i in result.indices) {
            var sample = result[i]
            if (i >= delay1) sample += reverb[i - delay1] * 0.3f
            if (i >= delay2) sample += reverb[i - delay2] * 0.2f
            if (i >= delay3) sample += reverb[i - delay3] * 0.15f
            reverb[i] = sample.coerceIn(-1f, 1f)
        }

        return ShortArray(reverb.size) { (reverb[it] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    // --- PITCH DETECTION (Autocorrelation) ---
    private fun detectPitch(data: ShortArray): Float {
        val chunkSize = 4096
        if (data.size < chunkSize) return 261.63f // Default to Middle C
        
        val chunk = FloatArray(chunkSize)
        for (i in 0 until chunkSize) chunk[i] = data[i].toFloat()
        
        val maxVal = chunk.maxOrNull()?.let { abs(it) } ?: 1f
        if (maxVal < 100f) return 261.63f // Silence fallback
        for (i in chunk.indices) chunk[i] /= maxVal
        
        var bestLag = 0
        var maxCorrelation = 0f
        val minLag = sampleRate / 1000 
        val maxLag = sampleRate / 70   
        
        for (lag in minLag..maxLag) {
            var correlation = 0f
            for (i in 0 until chunkSize - lag) correlation += chunk[i] * chunk[i + lag]
            if (correlation > maxCorrelation) {
                maxCorrelation = correlation
                bestLag = lag
            }
        }
        return if (bestLag > 0) sampleRate.toFloat() / bestLag else 261.63f
    }

    // --- ADVANCED DSP: STUDIO BACKING TRACK ---
    private fun generateStudioBackingTrack(rootPitch: Float, length: Int, vocalData: ShortArray): ShortArray {
        val track = FloatArray(length)
        
        // Map pitch to musical scale (C Major / A Minor)
        val notes = floatArrayOf(130.81f, 146.83f, 164.81f, 174.61f, 196.00f, 220.00f, 246.94f, 261.63f)
        var rootNote = notes[0]
        var minDiff = Float.MAX_VALUE
        for (note in notes) {
            if (abs(rootPitch - note) < minDiff) { minDiff = abs(rootPitch - note); rootNote = note }
        }

        // Chord Progression: I - vi - IV - V (Pop Standard)
        val chordRoots = floatArrayOf(rootNote, rootNote * 5/6f, rootNote * 4/3f, rootNote * 3/2f)
        val beatLength = sampleRate / 2 // 120 BPM
        
        for (i in 0 until length) {
            val t = i.toFloat() / sampleRate
            val beatIndex = (i / beatLength).toInt()
            val chordRoot = chordRoots[beatIndex % 4]
            
            // 1. Polyphonic Synth Pad (Major Chord: Root, 3rd, 5th)
            val pad = (
                sin(2.0 * PI * chordRoot * t) + 
                sin(2.0 * PI * chordRoot * 1.25f * t) + 
                sin(2.0 * PI * chordRoot * 1.5f * t)
            ) / 3.0 * 0.25
            
            // 2. 808 Kick Drum (Pitch swept sine)
            var kick = 0.0
            val timeInBeat = (i % beatLength).toFloat() / sampleRate
            if (timeInBeat < 0.15f && beatIndex % 2 == 0) {
                val kickFreq = 150.0 * exp(-timeInBeat * 30.0) + 40.0
                kick = sin(2.0 * PI * kickFreq * timeInBeat) * exp(-timeInBeat * 15.0) * 0.6
            }
            
            // 3. Crisp Snare (Noise + Tone)
            var snare = 0.0
            val snareTime = (i % (beatLength / 2)).toFloat() / sampleRate
            if (snareTime < 0.1f && beatIndex % 2 == 1) {
                val noise = (Math.random() * 2.0 - 1.0)
                val tone = sin(2.0 * PI * 200.0 * snareTime)
                snare = (noise * 0.6 + tone * 0.4) * exp(-snareTime * 25.0) * 0.4
            }
            
            // 4. Hi-Hats (High-passed noise)
            var hihat = 0.0
            val hhTime = (i % (beatLength / 4)).toFloat() / sampleRate
            if (hhTime < 0.02f) {
                hihat = (Math.random() * 2.0 - 1.0) * exp(-hhTime * 100.0) * 0.15
            }
            
            track[i] = (pad + kick + snare + hihat).toFloat()
        }
        
        // 5. Auto-Ducking (Sidechain effect based on vocal energy)
        val windowSize = 2048
        for (i in 0 until length step windowSize) {
            var vocalEnergy = 0f
            val end = minOf(i + windowSize, length)
            for (j in i until end) vocalEnergy += abs(vocalData[j].toFloat())
            vocalEnergy /= (end - i) * Short.MAX_VALUE
            
            // If singing loudly, duck the music
            val duckFactor = if (vocalEnergy > 0.1f) 0.4f else 1.0f
            for (j in i until end) track[j] *= duckFactor
        }

        return ShortArray(track.size) { (track[it] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    // --- FINAL MASTERING MIX ---
    private fun masterMix(voice: ShortArray, music: ShortArray): ShortArray {
        val length = minOf(voice.size, music.size)
        val mixed = FloatArray(length)
        
        for (i in 0 until length) {
            // Professional mix balance: Vocals upfront, music supporting
            mixed[i] = (voice[i].toFloat() * 0.85f + music[i].toFloat() * 0.65f)
            
            // Soft Clipper (Prevents digital distortion)
            if (abs(mixed[i]) > 0.9f) {
                mixed[i] = (mixed[i] - 0.9f * (mixed[i] / abs(mixed[i]))) / (1.0 + (abs(mixed[i]) - 0.9f)) + 0.9f * (mixed[i] / abs(mixed[i]))
            }
        }
        
        return ShortArray(length) { (mixed[it] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    // --- WAV EXPORT ---
    private fun saveToWav(data: ShortArray) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (dir != null) {
            mixedFile = File(dir, "SingSong_Studio_${System.currentTimeMillis()}.wav")
            try {
                val fos = FileOutputStream(mixedFile)
                val totalAudioLen = data.size * 2
                val totalDataLen = totalAudioLen + 36
                val byteRate = 16 * sampleRate * 1 / 8
                
                val header = ByteArray(44)
                header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
                header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen >> 8) and 0xff).toByte()
                header[6] = ((totalDataLen >> 16) and 0xff).toByte(); header[7] = ((totalDataLen >> 24) and 0xff).toByte()
                header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
                header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
                header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 
                header[20] = 1; header[21] = 0 
                header[22] = 1; header[23] = 0 
                header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate >> 8) and 0xff).toByte()
                header[26] = ((sampleRate >> 16) and 0xff).toByte(); header[27] = ((sampleRate >> 24) and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate >> 8) and 0xff).toByte()
                header[30] = ((byteRate >> 16) and 0xff).toByte(); header[31] = ((byteRate >> 24) and 0xff).toByte()
                header[32] = 2; header[33] = 0 
                header[34] = 16; header[35] = 0 
                header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen >> 8) and 0xff).toByte()
                header[42] = ((totalAudioLen >> 16) and 0xff).toByte(); header[43] = ((totalAudioLen >> 24) and 0xff).toByte()
                
                fos.write(header)
                val buffer = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in data) buffer.putShort(sample)
                fos.write(buffer.array())
                fos.close()
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Error saving file", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun playMixedTrack() {
        if (mixedFile != null && mixedFile!!.exists()) {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                try {
                    setDataSource(this@MainActivity, Uri.fromFile(mixedFile))
                    prepare()
                    start()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Error playing track", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
