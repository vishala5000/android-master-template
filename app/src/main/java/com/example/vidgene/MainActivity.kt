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
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
    
    @Volatile
    private var sniffedVideoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        setupWebView()
        setupListeners()
        setupBackPressHandler()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Storage permission denied - downloads may fail", Toast.LENGTH_LONG).show()
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
            setSupportMultipleWindows(false)
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false 
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                
                val mimeType = request.mimeType
                val isVideoMime = mimeType != null && mimeType.startsWith("video/")
                
                val isVideoFile = url.endsWith(".mp4", ignoreCase = true) || 
                                  url.endsWith(".webm", ignoreCase = true) || 
                                  url.endsWith(".mkv", ignoreCase = true) ||
                                  url.endsWith(".mov", ignoreCase = true)

                if (isVideoFile || isVideoMime) {
                    if (!url.startsWith("blob:") && !url.contains("pixel") && !url.contains("tracking") && url.length > 50) {
                        sniffedVideoUrl = url
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = WebChromeClient()

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
            val urlToDownload = sniffedVideoUrl
            
            if (urlToDownload != null && urlToDownload.isNotEmpty()) {
                val filename = "vd_video_${System.currentTimeMillis()}.mp4"
                downloadFile(urlToDownload, filename)
                sniffedVideoUrl = null 
            } else {
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
                        Toast.makeText(this, "No video detected. Play the video first or try a different site.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) return

        val url = if (query.startsWith("http://") || query.startsWith("https://")) {
            query
        } else {
            val searchQuery = URLEncoder.encode("$query 1080p full length video", "UTF-8")
            "https://www.google.com/search?q=$searchQuery"
        }
        
        sniffedVideoUrl = null 
        webView.loadUrl(url)
        etSearch.setText("")
        hideKeyboard()
    }

    private fun downloadFile(fileUrl: String, rawFilename: String) {
        val filename = rawFilename.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        
        // Validate download directory
        val vidzDir = File(Environment.getExternalStorageDirectory(), "vidz")
        if (!vidzDir.exists() && !vidzDir.mkdirs()) {
            Toast.makeText(this, "Cannot create download folder", Toast.LENGTH_LONG).show()
            return
        }
        
        Toast.makeText(this, "Starting download: $filename", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val outFile = File(vidzDir, filename)
                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.setRequestProperty("User-Agent", webView.settings.userAgentString)
                val cookie = CookieManager.getInstance().getCookie(fileUrl)
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie)
                }
                
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned ${connection.responseCode}")
                }

                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(outFile)

                val data = ByteArray(8192)
                var count: Int
                var totalBytes = 0L
                while (inputStream.read(data).also { count = it } != -1) {
                    outputStream.write(data, 0, count)
                    totalBytes += count
                }
                
                outputStream.close()
                inputStream.close()
                connection.disconnect()

                if (totalBytes < 1000) {
                    outFile.delete()
                    throw Exception("File too small - likely not a video")
                }

                runOnUiThread {
                    Toast.makeText(this, "✓ Saved to /vidz/$filename (${totalBytes / 1024 / 1024}MB)", Toast.LENGTH_LONG).show()
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
}
