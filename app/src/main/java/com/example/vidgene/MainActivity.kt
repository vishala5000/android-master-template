package com.example.vd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var fabDownload: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        setupWebView()
        setupListeners()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        fabDownload = findViewById(R.id.fabDownload)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Allow 'All Files Access' to save videos to /vidz folder", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            // Set a standard mobile user agent to prevent sites from blocking the WebView
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // Let the WebView handle all navigation
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Handles fullscreen video playback
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
            }
        }

        // Intercepts direct media download links
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
            downloadFile(url, filename)
        }
    }

    private fun setupListeners() {
        btnSearch.setOnClickListener { performSearch() }
        
        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                performSearch()
                true
            } else false
        }

        fabDownload.setOnClickListener {
            // Inject JavaScript to find the currently loaded video URL
            val js = """
                (function() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        if (videos[i].src && videos[i].src.startsWith('http')) return videos[i].src;
                        var sources = videos[i].querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            if (sources[j].src && sources[j].src.startsWith('http')) return sources[j].src;
                        }
                    }
                    return '';
                })();
            """.trimIndent()

            webView.evaluateJavascript(js) { result ->
                val videoUrl = result?.replace("\"", "")?.trim()
                if (videoUrl != null && videoUrl.isNotEmpty() && videoUrl != "null" && videoUrl != "''") {
                    val filename = "vd_video_${System.currentTimeMillis()}.mp4"
                    downloadFile(videoUrl, filename)
                } else {
                    Toast.makeText(this, "No video found on this page. Try clicking a direct video link.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) return

        val url = if (query.startsWith("http://") || query.startsWith("https://")) {
            query
        } else {
            // Appends "1080p full length video" to force high-quality full-length search results
            val searchQuery = URLEncoder.encode("$query 1080p full length video", "UTF-8")
            "https://www.google.com/search?q=$searchQuery"
        }
        
        webView.loadUrl(url)
        etSearch.setText("")
        hideKeyboard()
    }

    private fun downloadFile(fileUrl: String, rawFilename: String) {
        // Sanitize filename to prevent crashes
        val filename = rawFilename.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        
        Toast.makeText(this, "Starting download: $filename", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // Creates /storage/emulated/0/vidz/
                val vidzDir = File(Environment.getExternalStorageDirectory(), "vidz")
                if (!vidzDir.exists()) vidzDir.mkdirs()
                
                val outFile = File(vidzDir, filename)
                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(outFile)

                val data = ByteArray(1024)
                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    outputStream.write(data, 0, count)
                }
                
                outputStream.close()
                inputStream.close()
                connection.disconnect()

                runOnUiThread {
                    Toast.makeText(this, "Saved to internal storage /vidz/$filename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
