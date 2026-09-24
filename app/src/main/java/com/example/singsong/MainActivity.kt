package com.example.singsong

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
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

    // C Major Scale (2 octaves) for quantization
    private val musicScale = floatArrayOf(
        130.81f, 146.83f, 164.81f, 174.61f, 196.00f, 220.00f, 246.94f, // C3 to B3
        261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, // C4 to B4
        523.25f, 587.33f, 659.25f, 698.46f, 783.99f, 880.00f, 987.77f  // C5 to B5
    )

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
        tvStatus.text = "Recording... Sing your melody!"
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
            processDynamicMix(rawData.toShortArray())
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        tvStatus.text = "Analyzing melody & generating matching music..."
        fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        
        recordingThread?.join(3000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processDynamicMix(rawData: ShortArray) {
        if (rawData.isEmpty()) {
            runOnUiThread { tvStatus.text = "No audio recorded." }
            return
        }

        // 1. Apply Studio Vocal Chain (EQ, Compression, Reverb)
        val processedVocal = applyStudioVocalChain(rawData)
        
        // 2. Analyze melody in windows to auto-match music
        val melodyMap = extractMelodyMap(processedVocal)
        
        // 3. Generate Backing Track that dynamically follows the melody
        val backingTrack = generateDynamicBackingTrack(processedVocal.size, melodyMap)
        
        // 4. Final Mastering Mix
        val mixedData = masterMix(processedVocal, backingTrack)
        
        // 5. Export High-Quality WAV
        saveToWav(mixedData)
        
        runOnUiThread {
            tvStatus.text = "Mastering complete! Music matches your melody."
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
            val prevInput = if (i > 0) data[i-1].toFloat() / Short.MAX_VALUE else 0f
            result[i] = hpCutoff * (hpPrev + current - prevInput)
            hpPrev = result[i]
        }

        // 2. Soft-Knee Compressor
        val threshold = 0.3f
        val ratio = 4.0f
        for (i in result.indices) {
            val absVal = abs(result[i])
            if (absVal > threshold) {
                val sign = if (result[i] >= 0) 1f else -1f
                result[i] = sign * (threshold + (absVal - threshold) / ratio)
            }
        }

        // 3. Algorithmic Hall Reverb
        val reverb = FloatArray(result.size)
        val delay1 = sampleRate / 10
        val delay2 = sampleRate / 15
        val delay3 = sampleRate / 22
        
        for (i in result.indices) {
            var sample = result[i]
            if (i >= delay1) sample += reverb[i - delay1] * 0.3f
            if (i >= delay2) sample += reverb[i - delay2] * 0.2f
            if (i >= delay3) sample += reverb[i - delay3] * 0.15f
            reverb[i] = sample.coerceIn(-1f, 1f)
        }

        return ShortArray(reverb.size) { (reverb[it] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    // --- MELODY EXTRACTION (Windowed Pitch Tracking) ---
    private fun extractMelodyMap(data: ShortArray): Map<Int, Float> {
        val melodyMap = mutableMapOf<Int, Float>()
        val windowSize = 4096 // ~92ms windows
        var currentPitch = 261.63f // Default to C4
        
        for (i in 0 until data.size step windowSize) {
            val end = minOf(i + windowSize, data.size)
            if (end - i < windowSize) break
            
            val chunk = FloatArray(windowSize)
            var energy = 0f
            for (j in 0 until windowSize) {
                chunk[j] = data[i + j].toFloat()
                energy += abs(chunk[j])
            }
            energy /= windowSize
            
            // Only detect pitch if there's actual singing (not silence)
            if (energy > 500f) {
                val maxVal = chunk.maxOrNull()?.let { abs(it) } ?: 1f
                if (maxVal > 100f) {
                    for (j in chunk.indices) chunk[j] /= maxVal
                    
                    var bestLag = 0
                    var maxCorrelation = 0f
                    val minLag = sampleRate / 1000 
                    val maxLag = sampleRate / 70   
                    
                    for (lag in minLag..maxLag) {
                        var correlation = 0f
                        for (j in 0 until windowSize - lag) correlation += chunk[j] * chunk[j + lag]
                        if (correlation > maxCorrelation) {
                            maxCorrelation = correlation
                            bestLag = lag
                        }
                    }
                    
                    if (bestLag > 0) {
                        val detectedFreq = sampleRate.toFloat() / bestLag
                        currentPitch = quantizeToScale(detectedFreq)
                    }
                }
            }
            melodyMap[i] = currentPitch
        }
        return melodyMap
    }

    private fun quantizeToScale(freq: Float): Float {
        var closest = musicScale[0]
        var minDiff = Float.MAX_VALUE
        for (note in musicScale) {
            val diff = abs(freq - note)
            if (diff < minDiff) {
                minDiff = diff
                closest = note
            }
        }
        return closest
    }

    // --- DYNAMIC BACKING TRACK (Auto-Matches Melody) ---
    private fun generateDynamicBackingTrack(length: Int, melodyMap: Map<Int, Float>): ShortArray {
        val track = FloatArray(length)
        val beatLength = sampleRate / 2 // 120 BPM (0.5s per beat)
        
        // Chord mappings for C Major scale degrees (Root, 3rd, 5th)
        val chordMap = mapOf(
            130.81f to floatArrayOf(130.81f, 164.81f, 196.00f), // C Major
            146.83f to floatArrayOf(146.83f, 196.00f, 220.00f), // D Minor
            164.81f to floatArrayOf(164.81f, 196.00f, 246.94f), // E Minor
            174.61f to floatArrayOf(174.61f, 220.00f, 261.63f), // F Major
            196.00f to floatArrayOf(196.00f, 246.94f, 293.66f), // G Major
            220.00f to floatArrayOf(220.00f, 261.63f, 329.63f), // A Minor
            246.94f to floatArrayOf(246.94f, 293.66f, 349.23f)  // B Diminished (use G major bass instead for stability)
        )

        for (i in 0 until length) {
            val t = i.toFloat() / sampleRate
            val beatIndex = (i / beatLength).toInt()
            val windowStart = beatIndex * beatLength * sampleRate.toInt()
            
            // Get the quantized pitch for this beat
            val currentPitch = melodyMap.entries.firstOrNull { it.key <= windowStart }?.value ?: 261.63f
            
            // Find the closest chord root in our map (simplified to C, D, E, F, G, A)
            val chordRoot = chordMap.keys.minByOrNull { abs(it - currentPitch) } ?: 196.00f
            val chord = chordMap[chordRoot] ?: floatArrayOf(196.00f, 246.94f, 293.66f)
            
            // 1. Dynamic Bassline (Follows the vocal melody root)
            val bass = sin(2.0 * PI * chordRoot * t) * 0.5f
            
            // 2. Polyphonic Synth Pad (Plays the chord)
            val pad = (
                sin(2.0 * PI * chord[0] * t) + 
                sin(2.0 * PI * chord[1] * t) + 
                sin(2.0 * PI * chord[2] * t)
            ) / 3.0f * 0.2f
            
            // 3. 808 Kick Drum (On beats 1 and 3)
            var kick = 0.0
            val timeInBeat = (i % beatLength).toFloat() / sampleRate
            if (timeInBeat < 0.15f && beatIndex % 2 == 0) {
                val kickFreq = 150.0 * exp(-timeInBeat * 30.0) + 40.0
                kick = sin(2.0 * PI * kickFreq * timeInBeat) * exp(-timeInBeat * 15.0) * 0.6
            }
            
            // 4. Crisp Snare (On beats 2 and 4)
            var snare = 0.0
            val snareTime = (i % (beatLength / 2)).toFloat() / sampleRate
            if (snareTime < 0.1f && beatIndex % 2 == 1) {
                val noise = (Math.random() * 2.0 - 1.0)
                val tone = sin(2.0 * PI * 200.0 * snareTime)
                snare = (noise * 0.6 + tone * 0.4) * exp(-snareTime * 25.0) * 0.4
            }
            
            // 5. Hi-Hats (8th notes)
            var hihat = 0.0
            val hhTime = (i % (beatLength / 4)).toFloat() / sampleRate
            if (hhTime < 0.02f) {
                hihat = (Math.random() * 2.0 - 1.0) * exp(-hhTime * 100.0) * 0.15f
            }
            
            track[i] = (bass + pad + kick.toFloat() + snare.toFloat() + hihat.toFloat())
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
            mixedFile = File(dir, "SingSong_Matched_${System.currentTimeMillis()}.wav")
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
