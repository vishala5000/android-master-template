package com.example.timer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val FPS = 30
        private const val BITRATE = 10_000_000 // 10 Mbps for perfect quality
    }

    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var etSeconds: EditText
    private lateinit var cbBackground: CheckBox
    private lateinit var btnPickImage: Button
    private lateinit var btnGenerate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var selectedImageUri: Uri? = null
    private var lastGeneratedUri: Uri? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.entries.all { it.value }) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            Toast.makeText(this, "Image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHours = findViewById(R.id.etHours)
        etMinutes = findViewById(R.id.etMinutes)
        etSeconds = findViewById(R.id.etSeconds)
        cbBackground = findViewById(R.id.cbBackground)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnGenerate = findViewById(R.id.btnGenerate)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        checkPermissions()

        cbBackground.setOnCheckedChangeListener { _, isChecked ->
            btnPickImage.isEnabled = isChecked
            if (!isChecked) selectedImageUri = null
        }

        btnPickImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        btnGenerate.setOnClickListener {
            val h = etHours.text.toString().toIntOrNull() ?: 0
            val m = etMinutes.text.toString().toIntOrNull() ?: 0
            val s = etSeconds.text.toString().toIntOrNull() ?: 0
            val totalSeconds = (h * 3600) + (m * 60) + s

            if (totalSeconds <= 0) {
                Toast.makeText(this, "Enter at least 1 second", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (cbBackground.isChecked && selectedImageUri == null) {
                Toast.makeText(this, "Pick background image first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Delete previous video
            lastGeneratedUri?.let { uri ->
                contentResolver.delete(uri, null, null)
                lastGeneratedUri = null
            }

            startGeneration(totalSeconds)
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun startGeneration(totalSeconds: Int) {
        btnGenerate.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Starting ultra-fast generation..."

        lifecycleScope.launch(Dispatchers.Default) {
            var pfd: ParcelFileDescriptor? = null
            try {
                // FIX: Use immutable 'val' to prevent smart-cast closure errors
                val bgBitmap: Bitmap? = if (cbBackground.isChecked && selectedImageUri != null) {
                    var tempBitmap: Bitmap? = null
                    contentResolver.openInputStream(selectedImageUri!!)?.use { input ->
                        val original = BitmapFactory.decodeStream(input)
                        if (original != null) {
                            tempBitmap = Bitmap.createScaledBitmap(original, WIDTH, HEIGHT, true)
                            if (original !== tempBitmap) original.recycle()
                        }
                    }
                    tempBitmap
                } else {
                    null
                }

                // Create output file DIRECTLY in MediaStore (no temp file = maximum speed)
                val resolver = contentResolver
                val fileName = "Timer_Countdown.mp4"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Timer")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create file")

                pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw Exception("Failed to open file")

                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // Video codec - maximum speed settings
                val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = videoCodec.createInputSurface()
                videoCodec.start()

                // Audio codec
                val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 88200)
                }
                audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec.start()

                var videoTrack = -1
                var audioTrack = -1
                var muxerStarted = false

                val videoInfo = MediaCodec.BufferInfo()
                val audioInfo = MediaCodec.BufferInfo()

                // Pre-generate 1 second of audio (150ms beep + 850ms silence)
                val oneSecondAudio = generateOneSecondAudio(44100, 150, 880.0)

                // Text rendering setup
                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 280f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(25f, 0f, 0f, Color.BLACK)
                }
                val textY = (HEIGHT / 2f) - ((textPaint.fontMetrics.descent + textPaint.fontMetrics.ascent) / 2f)

                val bgRingPaint = Paint().apply {
                    color = Color.parseColor("#333333")
                    style = Paint.Style.STROKE
                    strokeWidth = 30f
                    isAntiAlias = true
                }
                val progressRingPaint = Paint().apply {
                    color = Color.parseColor("#00E5FF")
                    style = Paint.Style.STROKE
                    strokeWidth = 30f
                    isAntiAlias = true
                }
                val ringRect = RectF((WIDTH - 800) / 2f, (HEIGHT - 800) / 2f, (WIDTH + 800) / 2f, (HEIGHT + 800) / 2f)

                withContext(Dispatchers.Main) { tvStatus.text = "Rendering at maximum speed..." }

                val totalFrames = totalSeconds * FPS
                var lastAudioSecond = -1

                // Main rendering loop - ultra optimized
                for (frame in 0 until totalFrames) {
                    val currentSecond = frame / FPS
                    val remaining = totalSeconds - currentSecond
                    if (remaining < 0) break

                    val timeStr = formatTime(remaining)

                    // Draw frame
                    val canvas = surface.lockCanvas(null)
                    
                    // Smart cast now works perfectly because bgBitmap is a 'val'
                    if (bgBitmap != null && !bgBitmap.isRecycled) {
                        canvas.drawBitmap(bgBitmap, null, Rect(0, 0, WIDTH, HEIGHT), null)
                    } else {
                        canvas.drawColor(Color.BLACK)
                    }
                    
                    canvas.drawArc(ringRect, -90f, 360f, false, bgRingPaint)
                    canvas.drawArc(ringRect, -90f, (remaining.toFloat() / totalSeconds) * 360f, false, progressRingPaint)
                    canvas.drawText(timeStr, WIDTH / 2f, textY, textPaint)
                    surface.unlockCanvasAndPost(canvas)

                    // Drain video output
                    var outIdx = videoCodec.dequeueOutputBuffer(videoInfo, 10000)
                    while (outIdx >= 0) {
                        if (videoInfo.size > 0) {
                            if (!muxerStarted) {
                                videoTrack = muxer.addTrack(videoCodec.outputFormat)
                                audioTrack = muxer.addTrack(audioCodec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            val buf = videoCodec.getOutputBuffer(outIdx)!!
                            buf.position(videoInfo.offset)
                            buf.limit(videoInfo.offset + videoInfo.size)
                            muxer.writeSampleData(videoTrack, buf, videoInfo)
                        }
                        videoCodec.releaseOutputBuffer(outIdx, false)
                        outIdx = videoCodec.dequeueOutputBuffer(videoInfo, 0)
                    }

                    // Feed audio once per second
                    if (currentSecond != lastAudioSecond && remaining > 0) {
                        lastAudioSecond = currentSecond
                        val inIdx = audioCodec.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val buf = audioCodec.getInputBuffer(inIdx)!!
                            buf.clear()
                            buf.put(oneSecondAudio, 0, min(oneSecondAudio.size, buf.remaining()))
                            val pts = (currentSecond.toLong() * 1_000_000L)
                            audioCodec.queueInputBuffer(inIdx, 0, oneSecondAudio.size, pts, 0)
                        }
                    }

                    // Drain audio output
                    outIdx = audioCodec.dequeueOutputBuffer(audioInfo, 10000)
                    while (outIdx >= 0) {
                        if (audioInfo.size > 0 && muxerStarted) {
                            val buf = audioCodec.getOutputBuffer(outIdx)!!
                            buf.position(audioInfo.offset)
                            buf.limit(audioInfo.offset + audioInfo.size)
                            muxer.writeSampleData(audioTrack, buf, audioInfo)
                        }
                        audioCodec.releaseOutputBuffer(outIdx, false)
                        outIdx = audioCodec.dequeueOutputBuffer(audioInfo, 0)
                    }

                    // Update progress every second
                    if (frame % FPS == 0) {
                        val pct = (frame * 100) / totalFrames
                        withContext(Dispatchers.Main) {
                            progressBar.progress = pct
                            tvStatus.text = "Generating: $pct%"
                        }
                    }
                }

                // Finalize video stream
                videoCodec.signalEndOfInputStream()
                var done = false
                while (!done) {
                    val outIdx = videoCodec.dequeueOutputBuffer(videoInfo, 10000)
                    if (outIdx >= 0) {
                        if (videoInfo.size > 0 && muxerStarted) {
                            val buf = videoCodec.getOutputBuffer(outIdx)!!
                            buf.position(videoInfo.offset)
                            buf.limit(videoInfo.offset + videoInfo.size)
                            muxer.writeSampleData(videoTrack, buf, videoInfo)
                        }
                        if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        videoCodec.releaseOutputBuffer(outIdx, false)
                    }
                }

                // Finalize audio stream
                val inIdx = audioCodec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    audioCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                done = false
                while (!done) {
                    val outIdx = audioCodec.dequeueOutputBuffer(audioInfo, 10000)
                    if (outIdx >= 0) {
                        if (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        audioCodec.releaseOutputBuffer(outIdx, false)
                    }
                }

                // Cleanup
                videoCodec.stop(); videoCodec.release()
                audioCodec.stop(); audioCodec.release()
                muxer.stop(); muxer.release()
                bgBitmap?.recycle()

                // Mark file as complete
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                lastGeneratedUri = uri

                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Saved to Movies/Timer!"
                    btnGenerate.isEnabled = true
                    Toast.makeText(this@MainActivity, "Lightning-fast video saved!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    tvStatus.text = "Error"
                    btnGenerate.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pfd?.close()
            }
        }
    }

    private fun formatTime(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString(8) {
            if (h < 10) append('0'); append(h).append(':')
            if (m < 10) append('0'); append(m).append(':')
            if (s < 10) append('0'); append(s)
        }
    }

    private fun generateOneSecondAudio(sampleRate: Int, beepDurationMs: Int, freq: Double): ByteArray {
        val totalSamples = sampleRate
        val beepSamples = (sampleRate * beepDurationMs) / 1000
        val data = ByteArray(totalSamples * 2)
        val fade = sampleRate / 100
        
        var idx = 0
        for (i in 0 until totalSamples) {
            var sample = 0.0
            if (i < beepSamples) {
                var env = 1.0
                if (i < fade) env = i.toDouble() / fade
                else if (i > beepSamples - fade) env = (beepSamples - i).toDouble() / fade
                sample = sin(2.0 * Math.PI * i * freq / sampleRate) * env * 30000
            }
            
            val sampleInt = sample.toInt()
            data[idx++] = (sampleInt and 0x00FF).toByte()
            data[idx++] = ((sampleInt and 0xFF00) shr 8).toByte()
        }
        return data
    }
}
