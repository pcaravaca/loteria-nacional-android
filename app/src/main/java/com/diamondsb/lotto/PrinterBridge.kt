package com.diamondsb.lotto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import woyou.aidlservice.jiuiv5.ICallback
import woyou.aidlservice.jiuiv5.IWoyouService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sunmi V2 built-in thermal printer.
 *
 * The contract in `app/src/main/aidl/` was reverse-engineered from the service actually
 * installed on the terminal (`woyou.aidlservice.jiuiv5`,
 * SunmiPrinterService_v4.4.1_V2_release). Bind action: `woyou.aidlservice.jiuiv5.IWoyouService`.
 *
 * Print methods are asynchronous — the service returns immediately and reports back through
 * [ICallback] — so every call here waits for that callback (bounded) and returns its outcome.
 *
 * [printHtml] is different: the ticket HTML is rendered off-screen in a WebView, captured to a
 * bitmap and then printed, so it is reported back through [onResult] instead of a return value.
 */
class PrinterBridge(private val context: Context) {

    /** Receives the outcome of an asynchronous print, as a JSON string. */
    var onResult: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Prints are waited on here, never on the main thread: the render callback arrives on the
     * main thread and blocking it for the whole print would freeze the UI (ANR risk).
     * Recreated on demand after [unbind], so a bind/unbind cycle keeps working.
     */
    private var printExecutor: ExecutorService? = null

    private fun printerExecutor(): ExecutorService {
        val existing = printExecutor
        if (existing != null && !existing.isShutdown) return existing
        val created = Executors.newSingleThreadExecutor()
        printExecutor = created
        return created
    }

    private var service: IWoyouService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                service = IWoyouService.Stub.asInterface(binder)
                bound = true
                // Best-effort printer initialisation; the result arrives on the callback.
                service?.printerInit(NoopCallback)
            } catch (t: Throwable) {
                service = null
                bound = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun bind() {
        try {
            val intent = Intent("woyou.aidlservice.jiuiv5.IWoyouService").apply {
                setPackage("woyou.aidlservice.jiuiv5")
            }
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            bound = false
        }
    }

    fun unbind() {
        try {
            if (bound) context.unbindService(connection)
        } catch (_: Throwable) {
            // already unbound
        }
        bound = false
        service = null
        printExecutor?.shutdownNow()
        printExecutor = null
    }

    /** Returns a JSON string with the printer's current status. */
    fun getPrinterStatus(): String {
        val svc = service
        if (svc == null) return result(false, IllegalStateException("printer service not bound"))

        val paper = try { svc.getPrinterPaper() } catch (_: Throwable) { 0 }
        val state = try { svc.updatePrinterState() } catch (_: Throwable) { -1 }

        // Sunmi V2 logic:
        // paper == 1 usually means paper present.
        // state bits: varies, but often has bits for cover/lid.
        // Note: We will refine these bitmasks as we see real device output.
        return JSONObject().apply {
            put("paperPresent", paper == 1)
            put("coverClosed", state != -1 && (state and 0x01) != 0) // Placeholder bitmask
            put("ready", state != -1 && state != 0)
            put("paperCount", 0) // Placeholder, if supported later
        }.toString()
    }

    fun deviceInfo(): String = JSONObject().apply {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getMetrics(metrics)
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("screenWidthPx", metrics.widthPixels)
        put("screenHeightPx", metrics.heightPixels)
        put("densityDpi", metrics.densityDpi)
        put("printerBound", bound)
        put("printerReady", bound)
        val svc = service
        if (svc != null) {
            put("printerSerialNo", safe { svc.getPrinterSerialNo() })
            put("printerModal", safe { svc.getPrinterModal() })
            put("printerVersion", safe { svc.getPrinterVersion() })
            put("printerServiceVersion", safe { svc.getServiceVersion() })
            // Sunmi returns an int status; 1 is the "paper present" reading on the V2.
            put("printerPaper", safe { svc.getPrinterPaper().toString() })
            put("printerState", safe { svc.updatePrinterState().toString() })
        }
    }.toString()

    /**
     * Payload is either plain text or JSON:
     *   {"type":"text","text":"..."}
     *   {"type":"receipt","title":"...","lines":["..."],"footer":["..."]}
     *   {"type":"qr","value":"...","size":200}
     */
    fun printReceipt(payload: String): String {
        val svc = service ?: return result(false, IllegalStateException("printer service not bound"))
        return try {
            val json = JSONObject(payload)
            val waiter = PrintWaiter()
            when (json.optString("type", "text")) {
                "receipt" -> svc.printBitmap(renderReceipt(json), waiter)
                "qr" -> svc.printQRCode(json.optString("value"), json.optInt("size", 200), 1, waiter)
                else -> svc.printText(json.optString("text", payload), waiter)
            }
            if (waiter.await(PRINT_TIMEOUT_MS)) waiter.toJson()
            else result(false, IllegalStateException("printer callback timed out"))
        } catch (t: Throwable) {
            result(false, t)
        }
    }

    /**
     * Render the backend ticket HTML and print it. Asynchronous: returns `{queued:true}` and
     * reports the outcome through [onResult] (which the host forwards to the web app).
     *
     * Payload: `{"id":"...","html":"<full document>","baseUrl":"http://host/","widthMm":72}`
     */
    fun printHtml(payload: String) {
        val json = JSONObject(payload)
        val id = json.optString("id", "0")
        val html = json.optString("html", "")
        val baseUrl = json.optString("baseUrl", "http://localhost/")
        val widthMm = json.optDouble("widthMm", DEFAULT_TICKET_MM.toDouble()).toFloat()

        mainHandler.post {
            try {
                val svc = service
                    ?: throw IllegalStateException("printer service not bound")
                renderHtml(html, baseUrl, widthMm) { bitmap, error ->
                    if (error != null) {
                        emit(id, result(false, error))
                        return@renderHtml
                    }
                    val bmp = bitmap ?: run {
                        emit(id, result(false, IllegalStateException("render produced no bitmap")))
                        return@renderHtml
                    }
                    // This callback runs on the main thread; waiting for the printer here would
                    // freeze the UI for up to PRINT_TIMEOUT_MS (ANR risk). Print off-thread.
                    printerExecutor().execute {
                        val waiter = PrintWaiter()
                        try {
                            svc.printBitmap(bmp, waiter)
                            if (waiter.await(PRINT_TIMEOUT_MS)) emit(id, waiter.toJson())
                            else emit(id, result(false, IllegalStateException("printer callback timed out")))
                        } catch (t: Throwable) {
                            emit(id, result(false, t))
                        }
                    }
                }
            } catch (t: Throwable) {
                emit(id, result(false, t))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun emit(id: String, json: String) {
        val payload = JSONObject().apply {
            put("id", id)
            put("result", JSONObject(json))
        }.toString()
        mainHandler.post { onResult?.invoke(payload) }
    }

    private fun safe(block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "unavailable"
    }

    private fun result(ok: Boolean, error: Throwable? = null): String = JSONObject().apply {
        put("ok", ok)
        if (error != null) put("error", error.message ?: error.javaClass.simpleName)
    }.toString()

    /**
     * Lay the HTML out at its natural millimetre width (so font sizes map 1:1 to the paper),
     * capture the full content, then scale the bitmap to the printer's 384 px printable width.
     */
    private fun renderHtml(html: String, baseUrl: String, widthMm: Float, done: (Bitmap?, Throwable?) -> Unit) {
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = false
            useWideViewPort = false
            loadWithOverviewMode = false
            defaultTextEncodingName = "utf-8"
        }
        val targetWidthPx = mmToPx(widthMm)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidthPx, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                view.measure(widthSpec, heightSpec)
                val height = view.measuredHeight.coerceAtLeast(1)
                try {
                    val bitmap = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    view.layout(0, 0, targetWidthPx, height)
                    view.draw(canvas)
                    done(scaleToPrintWidth(bitmap), null)
                } catch (t: Throwable) {
                    done(null, t)
                } finally {
                    // A WebView is expensive; one is created per ticket, so release it or the
                    // kiosk leaks memory on every print.
                    try {
                        webView.destroy()
                    } catch (_: Throwable) {
                        // already gone
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // The ticket is a self-contained string; sub-resource failures are ignorable.
            }
        }

        try {
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            done(null, t)
            try {
                webView.destroy()
            } catch (_: Throwable) {
                // already gone
            }
        }
    }

    private fun scaleToPrintWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width == PRINT_WIDTH_PX) return bitmap
        val height = (bitmap.height.toFloat() * PRINT_WIDTH_PX / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, PRINT_WIDTH_PX, height, true)
    }

    private fun mmToPx(mm: Float): Int {
        val density = context.resources.displayMetrics.density
        return (mm / 25.4f * 160f * density).toInt().coerceAtLeast(PRINT_WIDTH_PX)
    }

    /** Ancho útil de un rollo de 58 mm: 384 px a 203 dpi (~48 mm). */
    private fun renderReceipt(json: JSONObject): Bitmap {
        val width = PRINT_WIDTH_PX
        val titleSize = 26f
        val bodySize = 20f
        val lineGap = 8f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = titleSize
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bodySize
        }

        val title = json.optString("title", "RECIBO")
        val lines = json.optJSONArray("lines") ?: org.json.JSONArray()
        val footer = json.optJSONArray("footer") ?: org.json.JSONArray()

        val height = (titleSize + lineGap) * 2 +
            (bodySize + lineGap) * (lines.length() + footer.length()) + 40f

        val bitmap = Bitmap.createBitmap(width, height.toInt().coerceAtLeast(80), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = titleSize + lineGap
        canvas.drawText(title, width / 2f, y, titlePaint)
        y += titleSize + lineGap * 2

        for (i in 0 until lines.length()) {
            canvas.drawText(lines.optString(i), 0f, y, bodyPaint)
            y += bodySize + lineGap
        }

        canvas.drawLine(0f, y, width.toFloat(), y, bodyPaint)
        y += bodySize + lineGap

        for (i in 0 until footer.length()) {
            canvas.drawText(footer.optString(i), 0f, y, bodyPaint)
            y += bodySize + lineGap
        }

        return bitmap
    }

    /** Waits for the printer service to report back. */
    private class PrintWaiter : ICallback.Stub() {
        private val latch = CountDownLatch(1)
        @Volatile private var ok = false
        @Volatile private var code = -1
        @Volatile private var message = ""

        // The service passes null here, so every callback parameter is nullable even
        // though AIDL declares them as plain Strings.
        override fun onPrintResult(code: Int, message: String?) {
            this.code = code
            this.message = message ?: ""
            this.ok = true
            latch.countDown()
        }

        override fun onRaiseException(code: Int, message: String?) {
            this.code = code
            this.message = message ?: ""
            this.ok = false
            latch.countDown()
        }

        override fun onReturnString(value: String?) {
            this.message = value ?: ""
            latch.countDown()
        }

        override fun onRunResult(success: Boolean) {
            this.ok = success
            latch.countDown()
        }

        fun await(ms: Long): Boolean = latch.await(ms, TimeUnit.MILLISECONDS)

        fun toJson(): String = JSONObject().apply {
            put("ok", ok)
            put("code", code)
            if (message.isNotEmpty()) put("message", message)
        }.toString()
    }

    private object NoopCallback : ICallback.Stub() {
        override fun onPrintResult(code: Int, message: String?) {}
        override fun onRaiseException(code: Int, message: String?) {}
        override fun onReturnString(value: String?) {}
        override fun onRunResult(success: Boolean) {}
    }

    companion object {
        const val PRINT_WIDTH_PX = 384

        /** Ancho de diseño por defecto: papel de 58 mm, como el Sunmi V2. */
        const val DEFAULT_TICKET_MM = 58f
        private const val PRINT_TIMEOUT_MS = 8000L
    }
}
