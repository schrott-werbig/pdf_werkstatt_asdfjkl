package ch.pdfwerkstatt.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF Werkstatt – native Hülle.
 * Die App besitzt keine Internet-Berechtigung; die Web-Oberfläche wird ausschliesslich
 * aus den App-Assets geladen. Jede andere Anfrage wird blockiert.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val incoming = ConcurrentHashMap<String, Uri>()
    private val pendingOpen = mutableListOf<JSONObject>()
    private var pageReady = false
    private var originalUri: Uri? = null
    private var originalWritable = false
    private var pendingSave: ByteArray? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = pendingSave
        pendingSave = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && bytes != null) {
            val ok = writeBytes(uri, bytes)
            notifySaved(ok, if (ok) "Gespeichert" else null)
        }
    }

    private val openDocuments = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            cb.onReceiveValue(null); return@registerForActivityResult
        }
        val data = result.data!!
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        cb.onReceiveValue(uris.toTypedArray())
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val cb = fileCallback
        fileCallback = null
        val uri = cameraUri
        cb?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/incoming/", IncomingHandler())
            .build()

        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") {
                    assetLoader.shouldInterceptRequest(url)?.let { return it }
                }
                // Alles andere wird blockiert – die App kommuniziert nie nach aussen.
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keine Navigation weg von der App (Links in PDFs werden nicht geöffnet)
                return request.url.host != "appassets.androidplatform.net"
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pageReady = true
                flushPendingOpen()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    if (params.isCaptureEnabled) {
                        val dir = File(cacheDir, "camera").apply { mkdirs() }
                        val f = File(dir, "scan-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                        cameraUri = uri
                        takePicture.launch(uri)
                    } else {
                        val types = params.acceptTypes.flatMap { it.split(",") }.map { it.trim() }
                            .mapNotNull { t ->
                                when {
                                    t.isEmpty() -> null
                                    t.startsWith(".") -> if (t.equals(".pdf", true)) "application/pdf" else null
                                    else -> t
                                }
                            }.distinct()
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = if (types.size == 1) types[0] else "*/*"
                            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        }
                        openDocuments.launch(intent)
                    }
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "PWAndroid")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            web.loadUrl("https://appassets.androidplatform.net/assets/www/pdf-werkstatt.html")
        } else {
            web.restoreState(savedInstanceState)
        }
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        if (uris.isEmpty()) return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            originalUri = uris[0]
            originalWritable = canWrite(uris[0], intent)
        } else {
            originalUri = null
            originalWritable = false
        }
        synchronized(pendingOpen) {
            for (u in uris) {
                val id = UUID.randomUUID().toString()
                incoming[id] = u
                pendingOpen.add(JSONObject().put("id", id).put("name", displayName(u)))
            }
        }
        // Intent nur einmal verarbeiten (z. B. nach Drehen des Geräts)
        intent.action = Intent.ACTION_MAIN
        flushPendingOpen()
    }

    private fun flushPendingOpen() {
        if (!pageReady) return
        val arr = JSONArray()
        synchronized(pendingOpen) {
            pendingOpen.forEach { arr.put(it) }
            pendingOpen.clear()
        }
        if (arr.length() == 0) return
        val js = "(function(l){function go(){ if(window.pwAndroidOpen&&window.S){ pwAndroidOpen(l); } else setTimeout(go,150); } go(); })($arr)"
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun canWrite(uri: Uri, intent: Intent): Boolean {
        if (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 && uri.scheme == "content") {
            // Manche Apps erlauben trotzdem Schreiben – vorsichtig prüfen
        }
        return try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) { }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokument.pdf"
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray): Boolean = try {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); true } ?: false
    } catch (e: Exception) {
        try { contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes); true } ?: false } catch (_: Exception) { false }
    }

    private fun notifySaved(ok: Boolean, msg: String?) {
        val m = JSONObject.quote(msg ?: "")
        web.post { web.evaluateJavascript("window.pwAndroidSaved && pwAndroidSaved($ok, $m)", null) }
    }

    /** Liefert eingehende Dateien an die Web-Oberfläche: https://appassets.androidplatform.net/incoming/<id> */
    private inner class IncomingHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val id = Uri.decode(path)
            val uri = incoming.remove(id) ?: return null
            return try {
                val type = contentResolver.getType(uri) ?: "application/pdf"
                WebResourceResponse(type, null, contentResolver.openInputStream(uri))
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Schnittstelle für die Web-Oberfläche (window.PWAndroid) */
    private inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                pendingSave = bytes
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try { createDocument.launch(i) } catch (e: Exception) { notifySaved(false, e.message) }
            }
        }

        @JavascriptInterface
        fun hasOriginal(): Boolean = originalUri != null && originalWritable

        @JavascriptInterface
        fun overwriteOriginal(base64: String): Boolean {
            val uri = originalUri ?: return false
            if (!originalWritable) return false
            return writeBytes(uri, Base64.decode(base64, Base64.DEFAULT))
        }

        @JavascriptInterface
        fun shareFile(name: String, mime: String, base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val f = File(dir, safe)
                    FileOutputStream(f).use { it.write(bytes) }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(safe, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Teilen"))
                } catch (e: Exception) {
                    notifySaved(false, e.message)
                }
            }
        }

        @JavascriptInterface
        fun printPdf(name: String, base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                pm.print(name, PdfPrintAdapter(name, bytes), PrintAttributes.Builder().build())
            }
        }
    }

    /** Druckt die fertigen PDF-Bytes direkt über den Android-Druckdienst */
    private class PdfPrintAdapter(private val name: String, private val bytes: ByteArray) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            cb.onLayoutFinished(info, true)
        }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            try {
                FileOutputStream(dest.fileDescriptor).use { it.write(bytes) }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            }
        }
    }
}
