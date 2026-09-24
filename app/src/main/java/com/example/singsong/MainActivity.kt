package com.example.singsong

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var btnPlay: MaterialButton

    private var isRecording = false
    private var isProcessing = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    private var mixedFileUri: android.net.Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val musicScale = floatArrayOf(
        130.81f, 146.83f, 164.81f, 174.61f, 196.00f, 220.00f, 246.94f,
        261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f,
        523.25f, 587.33f, 659.25f, 698.46f, 783.99f, 880.00f, 987.77f
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        fabRecord = findViewById(R.id.fabRecord)
        btnPlay = findViewById(R.id.btnPlay)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        checkPermissions()

        fabRecord.setOnClickListener {
            if (isProcessing) return@setOnClickListener
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
        if (bufferSize <= 0 || bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Toast.makeText(this, "Audio hardware not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        isRecording = true
        tvStatus.text = "Recording... Sing your melody!"
        fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        btnPlay.isEnabled = false
        
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Failed to initialize microphone", Toast.LENGTH_SHORT).show()
            isRecording = false
            return
        }
        
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
        isProcessing = true
        runOnUiThread {
            tvStatus.text = "Analyzing melody & generating matching music..."
            progressBar.visibility = View.VISIBLE
            fabRecord.isEnabled = false
        }
        
        recordingThread?.join(3000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processDynamicMix(rawData: ShortArray) {
        if (rawData.isEmpty()) {
            runOnUiThread { 
                tvStatus.text = "No audio recorded."
                progressBar.visibility = View.GONE
                fabRecord.isEnabled = true
                isProcessing = false
            }
            return
        }

        val processedVocal = applyStudioVocalChain(rawData)
        val melodyMap = extractMelodyMap(processedVocal)
        val backingTrack = generateDynamicBackingTrack(processedVocal.size, melodyMap)
        val mixedData = masterMix(processedVocal, backingTrack)
        
        saveToWav(mixedData)
        
        runOnUiThread {
            tvStatus.text = "Mastering complete! Saved to Music folder."
            progressBar.visibility = View.GONE
            fabRecord.isEnabled = true
            fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
            btnPlay.isEnabled = true
            isProcessing = false
        }
    }

    private fun applyStudioVocalChain(data: ShortArray): ShortArray {
        val result = FloatArray(data.size) { data[it].toFloat() / Short.MAX_VALUE }
        var hpPrev = 0f
        val hpCutoff = 0.98f
        
        for (i in result.indices) {
            val current = result[i]
            val prevInput = if (i > 0) data[i-1].toFloat() / Short.MAX_VALUE else 0f
            result[i] = hpCutoff * (hpPrev + current - prevInput)
            hpPrev = result[i]
        }

        val threshold = 0.3f
        val ratio = 4.0f
        for (i in result.indices) {
            val absVal = abs(result[i])
            if (absVal > threshold) {
                val sign = if (result[i] >= 0) 1f else -1f
                result[i] = sign * (threshold + (absVal - threshold) / ratio)
            }
        }

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

    private fun extractMelodyMap(data: ShortArray): Map<Int, Float> {
        val melodyMap = mutableMapOf<Int, Float>()
        val windowSize = 4096
        var currentPitch = 261.63f
        
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
                        currentPitch = quantizeToScale(sampleRate.toFloat() / bestLag)
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

    private fun generateDynamicBackingTrack(length: Int, melodyMap: Map<Int, Float>): ShortArray {
        val track = FloatArray(length)
        val beatLength = sampleRate / 2
        
        val chordMap = mapOf(
            130.81f to doubleArrayOf(130.81, 164.81, 196.00),
            146.83f to doubleArrayOf(146.83, 196.00, 220.00),
            164.81f to doubleArrayOf(164.81, 196.00, 246.94),
            174.61f to doubleArrayOf(174.61, 220.00, 261.63),
            196.00f to doubleArrayOf(196.00, 246.94, 293.66),
            220.00f to doubleArrayOf(220.00, 261.63, 329.63),
            246.94f to doubleArrayOf(246.94, 293.66, 349.23)
        )

        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            val beatIndex = i / beatLength
            val windowStart = beatIndex * beatLength * sampleRate
            
            val currentPitch = melodyMap.entries.firstOrNull { it.key <= windowStart }?.value ?: 261.63f
            val chordRoot = chordMap.keys.minByOrNull { abs(it - currentPitch) } ?: 196.00f
            val chord = chordMap[chordRoot] ?: doubleArrayOf(196.00, 246.94, 293.66)
            
            val bass = sin(2.0 * PI * chordRoot * t) * 0.5
            val pad = (sin(2.0 * PI * chord[0] * t) + sin(2.0 * PI * chord[1] * t) + sin(2.0 * PI * chord[2] * t)) / 3.0 * 0.2
            
            var kick = 0.0
            val timeInBeat = (i % beatLength).toDouble() / sampleRate
            if (timeInBeat < 0.15 && beatIndex % 2 == 0) {
                val kickFreq = 150.0 * exp(-timeInBeat * 30.0) + 40.0
                kick = sin(2.0 * PI * kickFreq * timeInBeat) * exp(-timeInBeat * 15.0) * 0.6
            }
            
            var snare = 0.0
            val snareTime = (i % (beatLength / 2)).toDouble() / sampleRate
            if (snareTime < 0.1 && beatIndex % 2 == 1) {
                val noise = (Math.random() * 2.0 - 1.0)
                val tone = sin(2.0 * PI * 200.0 * snareTime)
                snare = (noise * 0.6 + tone * 0.4) * exp(-snareTime * 25.0) * 0.4
            }
            
            var hihat = 0.0
            val hhTime = (i % (beatLength / 4)).toDouble() / sampleRate
            if (hhTime < 0.02) {
                hihat = (Math.random() * 2.0 - 1.0) * exp(-hhTime * 100.0) * 0.15
            }
            
            track[i] = (bass + pad + kick + snare + hihat).toFloat()
        }
        
        return ShortArray(track.size) { (track[it] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    private fun masterMix(voice: ShortArray, music: ShortArray): ShortArray {
        val length = minOf(voice.size, music.size)
        val mixed = FloatArray(length)
        
        for (i in 0 until length) {
            mixed[i] = (voice[i].toFloat() * 0.85f + music[i].toFloat() * 0.65f)
            if (abs(mixed[i]) > 0.9f) {
                mixed[i] = (mixed[i] - 0.9f * (mixed[i] / abs(mixed[i]))) / (1.0f + (abs(mixed[i]) - 0.9f)) + 0.9f * (mixed[i] / abs(mixed[i]))
            }
        }
        return ShortArray(length) { (mixed[it] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    private fun saveToWav(data: ShortArray) {
        val fileName = "SingSong_Mastered_${System.currentTimeMillis()}.wav"
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SingSong")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    val totalAudioLen = data.size * 2
                    val totalDataLen = totalAudioLen + 36
                    val byteRate = 16 * sampleRate * 1 / 8
                    
                    val header = ByteArray(44).apply {
                        this[0] = 'R'.toByte(); this[1] = 'I'.toByte(); this[2] = 'F'.toByte(); this[3] = 'F'.toByte()
                        this[4] = (totalDataLen and 0xff).toByte(); this[5] = ((totalDataLen shr 8) and 0xff).toByte()
                        this[6] = ((totalDataLen shr 16) and 0xff).toByte(); this[7] = ((totalDataLen shr 24) and 0xff).toByte()
                        this[8] = 'W'.toByte(); this[9] = 'A'.toByte(); this[10] = 'V'.toByte(); this[11] = 'E'.toByte()
                        this[12] = 'f'.toByte(); this[13] = 'm'.toByte(); this[14] = 't'.toByte(); this[15] = ' '.toByte()
                        this[16] = 16; this[17] = 0; this[18] = 0; this[19] = 0 
                        this[20] = 1; this[21] = 0 
                        this[22] = 1; this[23] = 0 
                        this[24] = (sampleRate and 0xff).toByte(); this[25] = ((sampleRate shr 8) and 0xff).toByte()
                        this[26] = ((sampleRate shr 16) and 0xff).toByte(); this[27] = ((sampleRate shr 24) and 0xff).toByte()
                        this[28] = (byteRate and 0xff).toByte(); this[29] = ((byteRate shr 8) and 0xff).toByte()
                        this[30] = ((byteRate shr 16) and 0xff).toByte(); this[31] = ((byteRate shr 24) and 0xff).toByte()
                        this[32] = 2; this[33] = 0 
                        this[34] = 16; this[35] = 0 
                        this[40] = (totalAudioLen and 0xff).toByte(); this[41] = ((totalAudioLen shr 8) and 0xff).toByte()
                        this[42] = ((totalAudioLen shr 16) and 0xff).toByte(); this[43] = ((totalAudioLen shr 24) and 0xff).toByte()
                    }
                    
                    outputStream.write(header)
                    val buffer = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (sample in data) buffer.putShort(sample)
                    outputStream.write(buffer.array())
                }
                // Mark as fully written
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                
                mixedFileUri = uri
            } catch (e: Exception) {
                e.printStackTrace()
                resolver.delete(uri, null, null)
                runOnUiThread { Toast.makeText(this, "Error saving file", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // FIXED: Assigned to local 'val' to prevent smart-cast errors
    private fun playMixedTrack() {
        val currentUri = mixedFileUri ?: return

        mediaPlayer?.release()
        
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build()
        val focusResult = audioManager?.requestAudioFocus(audioFocusRequest!!)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnCompletionListener { 
                audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
            }
            try {
                setDataSource(this@MainActivity, currentUri)
                prepare()
                start()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error playing track", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        mediaPlayer?.release()
        if (audioFocusRequest != null) {
            audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
        }
    }
}
