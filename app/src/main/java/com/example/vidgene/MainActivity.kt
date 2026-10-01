package com.example.vidgene

import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etCount: EditText
    private val prefsName = "VidGenePrefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        etCount = findViewById(R.id.etCount)

        findViewById<Button>(R.id.btnDownload).setOnClickListener {
            downloadAssets()
        }

        findViewById<Button>(R.id.btnGenerate).setOnClickListener {
            startGeneration()
        }
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun downloadAssets() {
        updateStatus("Downloading assets...")
        Thread {
            try {
                downloadFile("data.txt", "https://github.com/vishala5000/android-master-template/releases/download/assets/data.txt")
                downloadFile("font.ttf", "https://github.com/vishala5000/android-master-template/releases/download/assets/font.ttf")
                runOnUiThread { 
                    updateStatus("Assets downloaded successfully!") 
                    Toast.makeText(this, "Assets ready", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { 
                    updateStatus("Download failed: ${e.message}") 
                }
            }
        }.start()
    }

    private fun downloadFile(fileName: String, urlStr: String) {
        val file = File(filesDir, fileName)
        if (file.exists() && file.length() > 0) return
        URL(urlStr).openStream().use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun startGeneration() {
        val dataFile = File(filesDir, "data.txt")
        if (!dataFile.exists()) {
            Toast.makeText(this, "Please download assets first", Toast.LENGTH_SHORT).show()
            return
        }

        val requestedCount = etCount.text.toString().toIntOrNull() ?: 1
        if (requestedCount < 1) {
            Toast.makeText(this, "Enter a valid number (1 or more)", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("Preparing generation...")
        Thread {
            try {
                val lines = dataFile.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    runOnUiThread { updateStatus("Error: data.txt is empty") }
                    return@Thread
                }

                val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                val usedIndices = prefs.getStringSet("used", emptySet())?.map { it.toInt() }?.toMutableSet() ?: mutableSetOf()
                val available = (0 until lines.size).filter { !usedIndices.contains(it) }.toMutableList()

                if (available.isEmpty()) {
                    usedIndices.clear()
                    prefs.edit().putStringSet("used", emptySet()).apply()
                    available.addAll(0 until lines.size)
                    runOnUiThread { tvLog.append("All unique generations exhausted. Resetting for endless use.\n") }
                }

                val actualCount = minOf(requestedCount, available.size)
                runOnUiThread { updateStatus("Generating $actualCount videos...") }

                val random = java.util.Random()
                for (i in 1..actualCount) {
                    if (available.isEmpty()) break
                    val selectedIndex = available[random.nextInt(available.size)]
                    available.remove(selectedIndex)
                    usedIndices.add(selectedIndex)
                    prefs.edit().putStringSet("used", usedIndices.map { it.toString() }.toSet()).apply()

                    val targetText = lines[selectedIndex]
                    runOnUiThread { updateStatus("Generating video $i/$actualCount...") }

                    val shuffleTexts = mutableListOf<String>()
                    for (j in 1..3) {
                        shuffleTexts.add(lines[random.nextInt(lines.size)])
                    }

                    val cacheDir = cacheDir
                    val pngNames = listOf("s1.png", "s2.png", "s3.png", "reveal.png")
                    val texts = shuffleTexts + targetText
                    val durations = listOf("0.3", "0.3", "0.3", "3.0")
                    val mp4Paths = mutableListOf<String>()

                    for (j in 0..3) {
                        val pngFile = File(cacheDir, pngNames[j])
                        generateCard(texts[j], pngFile.absolutePath, isReveal = j == 3)
                        
                        val mp4File = File(cacheDir, "clip$j.mp4")
                        mp4Paths.add(mp4File.absolutePath)
                        
                        val cmd = "-y -loop 1 -i ${pngFile.absolutePath} -c:v libx264 -t ${durations[j]} -r 30 -pix_fmt yuv420p -vf scale=1080:1920 ${mp4File.absolutePath}"
                        FFmpegKit.execute(cmd)
                    }

                    val concatFile = File(cacheDir, "concat.txt")
                    concatFile.writeText(mp4Paths.joinToString("\n") { "file '$it'" })

                    val finalVideoName = "vidgene_${System.currentTimeMillis()}_$i.mp4"
                    val finalVideoFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), finalVideoName)

                    val concatCmd = "-y -f concat -safe 0 -i ${concatFile.absolutePath} -c copy ${finalVideoFile.absolutePath}"
                    FFmpegKit.execute(concatCmd)

                    runOnUiThread {
                        tvLog.append("✅ Generated: $finalVideoName\n")
                    }
                }
                runOnUiThread { updateStatus("Generation complete! Check app Movies folder.") }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Error: ${e.message}") }
            }
        }.start()
    }

    private fun generateCard(text: String, filePath: String, isReveal: Boolean) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(if (isReveal) Color.BLACK else Color.parseColor("#1a1a1a"))

        val cardWidth = 900f
        val cardHeight = 900f
        val cardLeft = (width - cardWidth) / 2f
        val cardTop = (height - cardHeight) / 2f

        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val rect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
        canvas.drawRoundRect(rect, 40f, 40f, paint)

        val textPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 64f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            try {
                val typeface = Typeface.createFromFile(File(filesDir, "font.ttf"))
                this.typeface = typeface
            } catch (e: Exception) {
                // Fallback to default font if font.ttf is missing/invalid
            }
        }

        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, (cardWidth - 100).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(12)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(cardLeft + cardWidth / 2f, cardTop + cardHeight / 2f - staticLayout.height / 2f)
        staticLayout.draw(canvas)
        canvas.restore()

        File(filePath).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }
}
