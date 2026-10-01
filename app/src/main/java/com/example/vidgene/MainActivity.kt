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
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class VideoInfo(
    val url: String,
    val width: Int,
    val height: Int,
    val duration: Double,
    val title: String,
    val pageUrl: String
) {
    val resolution: String get() = "${width}x${height}"
    val durationFormatted: String get() {
        val mins = (duration / 60).toInt()
        val secs = (duration % 60).toInt()
        return "${mins}m ${secs}s"
    }
    val is1080p: Boolean get() = width >= 1920 || height >= 1080
    val isFullLength: Boolean get() = duration >= 600
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var fabScan: FloatingActionButton
    private lateinit var videoListContainer: LinearLayout
    private lateinit var videoList: RecyclerView
    private lateinit var btnCloseList: ImageButton
    private lateinit var tvPanelTitle: TextView

    private val detectedVideos = mutableListOf<VideoInfo>()
    private lateinit var videoAdapter: VideoAdapter

    @Volatile
    private val sniffedVideoUrls = mutableMapOf<String, String>()

    private val VIDEO_DETECTOR_JS = """
        (function() {
            if (window.__vdInstalled) return;
            window.__vdInstalled = true;
            
            function scanVideos() {
                const videos = document.querySelectorAll('video');
                const results = [];
                videos.forEach(v => {
                    if (v.videoWidth > 0 && v.videoHeight > 0 && v.duration > 0 && !isNaN(v.duration)) {
                        let src = v.src || '';
                        if (!src || src.startsWith('blob:')) {
                            const source = v.querySelector('source');
                            if (source) src = source.src || '';
                        }
                        results.push({
                            url: src,
                            width: v.videoWidth,
                            height: v.videoHeight,
                            duration: v.duration,
                            title: document.title || 'Untitled',
                            pageUrl: window.location.href
                        });
                    }
                });
                return results;
            }
            
            function report() {
                const results = scanVideos();
                if (results.length > 0 && window.VDAndroid) {
                    window.VDAndroid.onVideosDetected(JSON.stringify(results));
                }
            }
            
            function attachToVideo(v) {
                if (v.dataset.vdAttached) return;
                v.dataset.vdAttached = 'true';
                v.addEventListener('loadedmetadata', report);
                v.addEventListener('play', report);
            }
            
            document.querySelectorAll('video').forEach(attachToVideo);
            
            const observer = new MutationObserver(() => {
                document.querySelectorAll('video').forEach(attachToVideo);
            });
            observer.observe(document.body || document.documentElement, {
                childList: true, subtree: true
            });
            
            setTimeout(report, 1000);
            setTimeout(report, 3000);
        })();
    """.trimIndent()

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
        fabScan = findViewById(R.id.fabScan)
        videoListContainer = findViewById(R.id.videoListContainer)
        videoList = findViewById(R.id.videoList)
        btnCloseList = findViewById(R.id.btnCloseList)
        tvPanelTitle = findViewById(R.id.tvPanelTitle)

        videoAdapter = VideoAdapter(detectedVideos) { video ->
            downloadVideo(video)
        }
        videoList.layoutManager = LinearLayoutManager(this)
        videoList.adapter = videoAdapter
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
            setSupportMultipleWindows(false)
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVideosDetected(jsonArray: String) {
                runOnUiThread {
                    try {
                        parseAndAddVideos(jsonArray)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }, "VDAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(VIDEO_DETECTOR_JS, null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                val mimeType = request.mimeType
                val isVideoMime = mimeType != null && mimeType.startsWith("video/")
                val isVideoFile = url.endsWith(".mp4", ignoreCase = true) ||
                        url.endsWith(".webm", ignoreCase = true) ||
                        url.endsWith(".mkv", ignoreCase = true) ||
                        url.endsWith(".mov", ignoreCase = true)

                if ((isVideoFile || isVideoMime) && !url.startsWith("blob:") && url.length > 50) {
                    val pageUrl = webView.url ?: ""
                    sniffedVideoUrls[pageUrl] = url
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

    private fun parseAndAddVideos(jsonArray: String) {
        val items = jsonArray.split("},{")

        items.forEach { item ->
            val cleaned = item.trim().removePrefix("[").removePrefix("{").removeSuffix("]").removeSuffix("}")
            val url = extractJsonString(cleaned, "url")
            val title = extractJsonString(cleaned, "title")
            val pageUrl = extractJsonString(cleaned, "pageUrl")
            val width = extractJsonInt(cleaned, "width")
            val height = extractJsonInt(cleaned, "height")
            val duration = extractJsonDouble(cleaned, "duration")

            if (url.isNotEmpty() && width > 0 && height > 0 && duration > 0) {
                val is1080p = width >= 1920 || height >= 1080
                val isFullLength = duration >= 600

                if (is1080p && isFullLength) {
                    val video = VideoInfo(url, width, height, duration, title, pageUrl)
                    if (detectedVideos.none { it.url == video.url }) {
                        detectedVideos.add(video)
                        videoAdapter.notifyDataSetChanged()
                        updatePanelVisibility()
                    }
                }
            }
        }
    }

    private fun extractJsonString(json: String, key: String): String {
        val pattern = "\"$key\":\"([^\"]*)\""
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJsonInt(json: String, key: String): Int {
        val pattern = "\"$key\":(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractJsonDouble(json: String, key: String): Double {
        val pattern = "\"$key\":([0-9.]+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun updatePanelVisibility() {
        if (detectedVideos.isEmpty()) {
            videoListContainer.visibility = View.GONE
            tvPanelTitle.text = "🎬 No 1080p Full-Length Videos Found"
        } else {
            videoListContainer.visibility = View.VISIBLE
            tvPanelTitle.text = "🎬 ${detectedVideos.size} 1080p Full-Length Video(s) Detected"
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

        fabScan.setOnClickListener {
            webView.evaluateJavascript(VIDEO_DETECTOR_JS, null)
            Toast.makeText(this, "Scanning page for 1080p videos...", Toast.LENGTH_SHORT).show()
        }

        btnCloseList.setOnClickListener {
            videoListContainer.visibility = View.GONE
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    videoListContainer.visibility == View.VISIBLE -> videoListContainer.visibility = View.GONE
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
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

        detectedVideos.clear()
        videoAdapter.notifyDataSetChanged()
        updatePanelVisibility()
        webView.loadUrl(url)
        etSearch.setText("")
        hideKeyboard()
    }

    private fun downloadVideo(video: VideoInfo) {
        var urlToDownload = video.url

        if (urlToDownload.isEmpty() || urlToDownload.startsWith("blob:")) {
            urlToDownload = sniffedVideoUrls[video.pageUrl] ?: ""
        }

        if (urlToDownload.isEmpty()) {
            Toast.makeText(this, "❌ Cannot download: video uses protected streaming", Toast.LENGTH_LONG).show()
            return
        }

        if (urlToDownload.endsWith(".m3u8") || urlToDownload.endsWith(".mpd")) {
            Toast.makeText(this, "❌ HLS/DASH streams require ffmpeg to download", Toast.LENGTH_LONG).show()
            return
        }

        val filename = "vd_${video.width}p_${video.duration.toInt()}s_${System.currentTimeMillis()}.mp4"
        downloadFile(urlToDownload, filename)
    }

    private fun downloadFile(fileUrl: String, rawFilename: String) {
        val filename = rawFilename.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val vidzDir = File(Environment.getExternalStorageDirectory(), "vidz")
        if (!vidzDir.exists() && !vidzDir.mkdirs()) {
            Toast.makeText(this, "Cannot create /vidz folder", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "⬇️ Downloading: $filename", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val outFile = File(vidzDir, filename)
                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", webView.settings.userAgentString)
                val cookie = CookieManager.getInstance().getCookie(fileUrl)
                if (cookie != null) connection.setRequestProperty("Cookie", cookie)
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${connection.responseCode}")
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

                runOnUiThread {
                    val sizeMB = String.format("%.1f", totalBytes / 1024.0 / 1024.0)
                    Toast.makeText(this, "✅ Saved: /vidz/$filename ($sizeMB MB)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    inner class VideoAdapter(
        private val videos: List<VideoInfo>,
        private val onClick: (VideoInfo) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF2A2A2A.toInt())
                val margin = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                layoutParams = margin
                textSize = 13f
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val video = videos[position]
            holder.tvTitle.text = "📺 ${video.resolution} • ${video.durationFormatted}\n${video.title}\n🔗 ${video.url.take(60)}..."
            holder.tvTitle.setOnClickListener { onClick(video) }
        }

        override fun getItemCount() = videos.size
    }
}
