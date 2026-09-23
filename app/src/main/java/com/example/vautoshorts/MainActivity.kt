package com.example.vautoshorts

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.Surface
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var quotesContainer: LinearLayout
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        quotesContainer = findViewById(R.id.quotesContainer)
        statusText = findViewById(R.id.statusText)
        val addBtn = findViewById<Button>(R.id.addQuoteBtn)
        val genBtn = findViewById<Button>(R.id.generateBtn)

        addBtn.setOnClickListener { addQuoteField("Enter quote here...") }
        genBtn.setOnClickListener { generateVideos() }

        // Add initial quote field
        addQuoteField("Enter your first motivational quote here...")
    }

    private fun addQuoteField(hint: String) {
        val editText = EditText(this).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 16) }
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            minLines = 2
            gravity = Gravity.CENTER
            textSize = 16f
        }
        quotesContainer.addView(editText)
    }

    private fun generateVideos() {
        val quotes = mutableListOf<String>()
        for (i in 0 until quotesContainer.childCount) {
            val text = (quotesContainer.getChildAt(i) as EditText).text.toString().trim()
            if (text.isNotEmpty() && !text.startsWith("Enter")) quotes.add(text)
        }

        if (quotes.isEmpty()) {
            statusText.text = "Please enter at least one valid quote."
            return
        }

        statusText.text = "Starting generation..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for ((index, quote) in quotes.withIndex()) {
                    withContext(Dispatchers.Main) { 
                        statusText.text = "Generating short ${index + 1} of ${quotes.size}..." 
                    }
                    createShortVideo(quote, index + 1)
                }
                withContext(Dispatchers.Main) { 
                    statusText.text = "Success! Videos saved to Internal Storage/Movies/VAutoShorts" 
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    statusText.text = "Error: ${e.message}" 
                }
            }
        }
    }

    private fun createShortVideo(quote: String, index: Int) {
        val width = 1080
        val height = 1920
        val frameRate = 30
        val durationSec = 5
        val totalFrames = frameRate * durationSec

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6000000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VAutoShorts")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "short_$index.mp4")
        if (file.exists()) file.delete()

        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        val paint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 120f
        }

        // Auto-scale text to fit within 900px width
        while (paint.measureText(quote) > 900f && paint.textSize > 40f) {
            paint.textSize -= 5f
        }

        for (frame in 0 until totalFrames) {
            val rect = Rect(0, 0, width, height)
            val canvas: Canvas = inputSurface.lockCanvas(rect)

            // Draw Background
            canvas.drawColor(Color.BLACK)

            // Draw Text (Centered)
            val x = width / 2f
            val y = height / 2f - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(quote, x, y, paint)

            inputSurface.unlockCanvasAndPost(canvas)

            // Drain encoder output to muxer
            drainEncoder(codec, muxer, bufferInfo, muxerTrackIndex).also { 
                if (it != -1) muxerTrackIndex = it 
            }

            // Pace the frames to ensure correct video duration
            Thread.sleep((1000 / frameRate).toLong())
        }

        // Finalize video
        codec.signalEndOfInputStream()
        drainEncoder(codec, muxer, bufferInfo, muxerTrackIndex, endOfStream = true)

        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
        inputSurface.release()
    }

    private fun drainEncoder(
        codec: MediaCodec, 
        muxer: MediaMuxer, 
        bufferInfo: MediaCodec.BufferInfo, 
        trackIndex: Int, 
        endOfStream: Boolean = false
    ): Int {
        var currentTrackIndex = trackIndex
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (bufferInfo.size > 0 && currentTrackIndex != -1) {
                        buffer?.position(bufferInfo.offset)
                        buffer?.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(currentTrackIndex, buffer!!, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    currentTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) break else return currentTrackIndex
                }
            }
        }
        return currentTrackIndex
    }
}
