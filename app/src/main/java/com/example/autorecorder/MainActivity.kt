package com.example.autorecorder

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.IOException
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: MaterialButton
    private lateinit var chkAutoZoom: CheckBox

    private val PERMISSION_REQUEST_CODE = 100
    private val SCREEN_CAPTURE_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        chkAutoZoom = findViewById(R.id.chkAutoZoom)

        createNotificationChannel()
        checkPermissions()

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                return@setOnClickListener
            }

            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "recording_channel",
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("autoZoom", chkAutoZoom.isChecked)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        }
    }
}

class RecordingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isPaused = false
    private var autoZoomEnabled = false

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var drawingOverlay: DrawingOverlayView? = null
    private var isDrawing = false

    private val videoWidth = 1920
    private val videoHeight = 1080
    private val videoDpi = 320

    private var outputFile: String? = null
    private var gradientAnimationHandler = Handler(Looper.getMainLooper())
    private var gradientAnimationRunnable: Runnable? = null
    private var gradientOffset = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        autoZoomEnabled = intent?.getBooleanExtra("autoZoom", false) ?: false

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        startForegroundNotification()
        createFloatingControlPanel()
        startRecording()

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, "recording_channel")
            .setContentTitle("Auto Recorder")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createFloatingControlPanel() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(12, 12, 12, 12)
        }

        val btnRecord = createFloatingButton("●", Color.RED) { toggleRecording() }
        val btnPause = createFloatingButton("⏸", Color.YELLOW) { pauseRecording() }
        val btnStop = createFloatingButton("⏹", Color.WHITE) { stopRecordingAndSave() }
        val btnDraw = createFloatingButton("✎", Color.CYAN) { toggleDrawing() }

        container.addView(btnRecord)
        container.addView(btnPause)
        container.addView(btnStop)
        container.addView(btnDraw)

        floatingView = container
        windowManager?.addView(floatingView, layoutParams)
    }

    private fun createFloatingButton(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val size = 100
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(8, 0, 8, 0)
            }
            setBackgroundColor(color)
            setPadding(16, 16, 16, 16)
            setOnClickListener { onClick() }
        }
    }

    private fun startRecording() {
        val timestamp = System.currentTimeMillis()
        outputFile = "AutoRecorder_$timestamp.mp4"

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoSize(videoWidth, videoHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(8000000)
            setVideoFrameRate(30)

            val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), outputFile)
            setOutputFile(tempFile.absolutePath)

            prepare()
        }

        val surface = mediaRecorder?.surface ?: return

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            videoWidth,
            videoHeight,
            videoDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        mediaRecorder?.start()
        isRecording = true
        
        if (autoZoomEnabled) {
            startAutoZoomAnimation()
        }
    }

    private fun startAutoZoomAnimation() {
        gradientAnimationRunnable = object : Runnable {
            override fun run() {
                if (isRecording && !isPaused) {
                    gradientOffset += 0.01f
                    if (gradientOffset > 1f) gradientOffset = 0f
                    
                    virtualDisplay?.resize(
                        (videoWidth * (0.9f + 0.1f * sin(gradientOffset * Math.PI * 2))).toInt(),
                        (videoHeight * (0.9f + 0.1f * sin(gradientOffset * Math.PI * 2))).toInt(),
                        videoDpi
                    )
                    
                    gradientAnimationHandler.postDelayed(this, 100)
                }
            }
        }
        gradientAnimationRunnable?.let { gradientAnimationHandler.post(it) }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            startRecording()
        } else {
            stopRecordingAndSave()
        }
    }

    private fun pauseRecording() {
        if (isRecording && !isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                isPaused = true
                Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show()
            }
        } else if (isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                isPaused = false
                Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecordingAndSave() {
        if (!isRecording) return

        try {
            gradientAnimationRunnable?.let { gradientAnimationHandler.removeCallbacks(it) }
            
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null

            val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), outputFile)
            if (tempFile.exists()) {
                saveToMediaStore(tempFile)
                tempFile.delete()
            }

            Toast.makeText(this, "Recording saved to Movies/AutoRecorder", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving recording", Toast.LENGTH_SHORT).show()
        }

        stopForegroundService()
    }

    private fun saveToMediaStore(tempFile: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputFile)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AutoRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            try {
                resolver.openOutputStream(it)?.use { outputStream ->
                    tempFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            } catch (e: IOException) {
                e.printStackTrace()
                resolver.delete(it, null, null)
            }
        }
    }

    private fun toggleDrawing() {
        isDrawing = !isDrawing
        if (isDrawing) {
            createDrawingOverlay()
        } else {
            removeDrawingOverlay()
        }
    }

    private fun createDrawingOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        drawingOverlay = DrawingOverlayView(this)
        windowManager?.addView(drawingOverlay, layoutParams)
    }

    private fun removeDrawingOverlay() {
        drawingOverlay?.let {
            windowManager?.removeView(it)
            drawingOverlay = null
        }
    }

    private fun stopForegroundService() {
        removeDrawingOverlay()
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundService()
    }

    inner class DrawingOverlayView(context: Context) : View(context) {
        private val paths = mutableListOf<Pair<Path, Paint>>()
        private var currentPath: Path? = null
        private var currentPaint: Paint? = null

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for ((path, paint) in paths) {
                canvas.drawPath(path, paint)
            }
            currentPath?.let { path ->
                currentPaint?.let { paint ->
                    canvas.drawPath(path, paint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath = Path()
                    currentPaint = Paint().apply {
                        color = Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = 8f
                        isAntiAlias = true
                        strokeJoin = Paint.Join.ROUND
                        strokeCap = Paint.Cap.ROUND
                    }
                    currentPath?.moveTo(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentPath?.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    currentPath?.let { path ->
                        currentPaint?.let { paint ->
                            paths.add(Pair(path, paint))
                        }
                    }
                    currentPath = null
                    currentPaint = null
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
