package com.diamondsb.lotto

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diamondsb.lotto.databinding.ActivityHomeBinding
import com.diamondsb.lotto.databinding.ItemSorteoNativeBinding
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.NumberFormat
import java.util.Locale

/**
 * Pantalla principal: saldo y proximos sorteos, con apuesta nativa.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var updater: Updater
    // Secciones que la app abre en el WebView (la API es la misma que la web).
    private enum class Modulo {
        SORTEOS, LOTERIAS, QUINIELAS, RIFAS, CASINO,
        MIS_APUESTAS, RESULTADOS, REPORTES, PERFIL
    }

    private var modulo = Modulo.SORTEOS
    private var tokenInyectado = false
    private var enPrimerPlano = false
    private val handler = Handler(Looper.getMainLooper())
    // El saldo se mueve en la web (rifas/casino) o en otro dispositivo: se consulta
    // solo cada 5 segundos para que la etiqueta quede siempre viva.
    private val refrescarSaldo = object : Runnable {
        override fun run() {
            if (enPrimerPlano) pedirSaldo()
            handler.postDelayed(this, 5000)
        }
    }

    private val sorteos = mutableListOf<JsonObject>()
    private val loterias = mutableMapOf<Long, JsonObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        if (!SessionStore.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvNombre.text = SessionStore.nombre.ifBlank { SessionStore.email }
        pintarSaldo()

        b.btnSalir.setOnClickListener {
            SessionStore.clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        prepararModulos()
        prepararImpresora()
        seleccionar(Modulo.SORTEOS)

        updater = Updater(this, BuildConfig.LOTERIA_URL)
        updater.check()

        cargar()
    }

    override fun onPause() {
        super.onPause()
        enPrimerPlano = false
        handler.removeCallbacks(refrescarSaldo)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refrescarSaldo)
        if (::updater.isInitialized) updater.unregister()
        tts?.shutdown()
        tts = null
        impresora?.unbind()
        impresora = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        handler.removeCallbacks(refrescarSaldo)
        handler.post(refrescarSaldo)
        // Cada vez que la app vuelve a primer plano revisa si hay versión nueva.
        if (::updater.isInitialized) updater.check()
        // El saldo puede haber cambiado por una apuesta u otro dispositivo.
        ApiClient.api.me().enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, res: Response<JsonObject>) {
                if (res.isSuccessful() && res.body() != null) {
                    val u = res.body()!!
                    SessionStore.saldo = num(u, "saldo")
                    pintarSaldo()
                }
            }

            override fun onFailure(call: Call<JsonObject>, t: Throwable) {}
        })
    }

    private fun pintarSaldo() {
        b.tvSaldo.text = money(SessionStore.saldo)
    }

    private fun cargar() {
        b.pbCargando.visibility = View.VISIBLE
        b.tvVacio.visibility = View.GONE

        ApiClient.api.loterias().enqueue(object : Callback<JsonArray> {
            override fun onResponse(call: Call<JsonArray>, res: Response<JsonArray>) {
                val arr = res.body() ?: JsonArray()
                for (i in 0 until arr.size()) {
                    val l = arr[i].asJsonObject
                    loterias[l.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L] = l
                }
                cargarSorteos()
            }

            override fun onFailure(call: Call<JsonArray>, t: Throwable) {
                b.pbCargando.visibility = View.GONE
                b.tvVacio.visibility = View.VISIBLE
                b.tvVacio.text = "Sin conexion"
            }
        })
    }

    private fun cargarSorteos() {
        ApiClient.api.proximosSorteos().enqueue(object : Callback<JsonArray> {
            override fun onResponse(call: Call<JsonArray>, res: Response<JsonArray>) {
                b.pbCargando.visibility = View.GONE
                sorteos.clear()
                val arr = res.body() ?: JsonArray()
                for (i in 0 until arr.size()) {
                    sorteos.add(arr[i].asJsonObject)
                }
                if (sorteos.isEmpty()) {
                    b.tvVacio.visibility = View.VISIBLE
                    b.tvVacio.text = "No hay sorteos abiertos ahora mismo"
                    return
                }
                b.rvSorteos.layoutManager = LinearLayoutManager(this@HomeActivity)
                b.rvSorteos.adapter = SorteosAdapter()
            }

            override fun onFailure(call: Call<JsonArray>, t: Throwable) {
                b.pbCargando.visibility = View.GONE
                b.tvVacio.visibility = View.VISIBLE
                b.tvVacio.text = "Sin conexion"
            }
        })
    }

    private fun apostar(sorteo: JsonObject) {
        val loteriaID = sorteo.get("loteria_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val loteria = loterias[loteriaID]
        val maxNum = loteria?.get("max_numeros")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val longitud = loteria?.get("longitud_num")?.takeIf { !it.isJsonNull }?.asInt ?: 2
        val costo = loteria?.get("costo_numero")?.takeIf { !it.isJsonNull }?.asLong ?: 0L

        val entradas = mutableListOf<EditText>()
        val caja = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        for (i in 0 until maxNum) {
            val et = EditText(this).apply {
                hint = "Número ${i + 1} (${longitud} dígitos)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(getColor(R.color.lot_text))
                setHintTextColor(getColor(R.color.lot_text_muted))
            }
            entradas.add(et)
            caja.addView(et)
        }

        AlertDialog.Builder(this)
            .setTitle(sorteo.get("titulo")?.takeIf { !it.isJsonNull }?.asString ?: "Apostar")
            .setMessage("Total a pagar: " + money(costo * maxNum.toDouble()) + "\nSaldo: " + money(SessionStore.saldo))
            .setView(caja)
            .setPositiveButton("Confirmar") { _, _ ->
                val numeros = entradas.mapNotNull { it.text.toString().trim().takeIf { v -> v.isNotEmpty() } }
                    .mapNotNull { v -> v.toLongOrNull() }
                val repetidos = numeros.size != numeros.distinct().size
                if (numeros.isEmpty() || numeros.size > maxNum || repetidos ||
                    numeros.any { n -> n >= Math.pow(10.0, longitud.toDouble()).toLong() }
                ) {
                    Toast.makeText(this, "Revisá los números", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                confirmarApuesta(sorteo.get("id").asLong, numeros)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarApuesta(sorteoID: Long, numeros: List<Long>) {
        val body = JsonObject().apply {
            addProperty("sorteo_id", sorteoID)
            val arr = JsonArray()
            numeros.forEach { arr.add(it) }
            add("numeros", arr)
        }
        Toast.makeText(this, "Enviando apuesta…", Toast.LENGTH_SHORT).show()
        ApiClient.api.crearApuesta(body).enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, res: Response<JsonObject>) {
                val err = res.errorBody()?.string()
                if (!res.isSuccessful()) {
                    Toast.makeText(
                        this@HomeActivity,
                        if (err.isNullOrBlank()) "No se pudo registrar (código ${res.code()})" else mensajeDe(err),
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }
                Toast.makeText(this@HomeActivity, "Apuesta registrada", Toast.LENGTH_SHORT).show()
                ApiClient.api.me().enqueue(object : Callback<JsonObject> {
                    override fun onResponse(call: Call<JsonObject>, r: Response<JsonObject>) {
                        if (r.isSuccessful() && r.body() != null) {
                            SessionStore.saldo = num(r.body()!!, "saldo")
                            pintarSaldo()
                        }
                    }

                    override fun onFailure(call: Call<JsonObject>, t: Throwable) {}
                })
            }

            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Toast.makeText(this@HomeActivity, "Sin conexion", Toast.LENGTH_LONG).show()
            }
        })
    }

    /** Saca el campo "error" del JSON de error del backend. */
    private fun mensajeDe(json: String): String = try {
        com.google.gson.JsonParser.parseString(json).asJsonObject.get("error")?.asString ?: json
    } catch (_: Exception) {
        json
    }

    private fun num(obj: JsonObject, key: String): Double =
        obj.get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

    private fun money(v: Double): String {
        val nf = NumberFormat.getNumberInstance(Locale("es", "CR"))
        nf.maximumFractionDigits = 0
        return "₡" + nf.format(v)
    }

    private fun fecha(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val crudo = iso.replace("T", " ").take(16)
        return crudo
    }

    private inner class SorteosAdapter : RecyclerView.Adapter<SorteosAdapter.VH>() {

        inner class VH(val binding: ItemSorteoNativeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSorteoNativeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = sorteos.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = sorteos[position]
            val hb = holder.binding
            val loteriaID = s.get("loteria_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val loteria = loterias[loteriaID]
            hb.tvLoteria.text = loteria?.get("nombre")?.takeIf { !it.isJsonNull }?.asString ?: "Lotería"
            hb.tvTitulo.text = s.get("titulo")?.takeIf { !it.isJsonNull }?.asString ?: ""
            hb.tvFecha.text = fecha(s.get("fecha")?.takeIf { !it.isJsonNull }?.asString)
            val costo = loteria?.get("costo_numero")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            hb.tvCosto.text = money(costo.toDouble()) + " / número"
            hb.btnJugar.isEnabled = true
            hb.btnJugar.setOnClickListener { apostar(s) }
        }
    }

private fun prepararModulos() {
    b.tabSorteos.setOnClickListener { seleccionar(Modulo.SORTEOS) }
    b.tabLoterias.setOnClickListener { seleccionar(Modulo.LOTERIAS) }
    b.tabQuinielas.setOnClickListener { seleccionar(Modulo.QUINIELAS) }
    b.tabRifas.setOnClickListener { seleccionar(Modulo.RIFAS) }
    b.tabCasino.setOnClickListener { seleccionar(Modulo.CASINO) }

    // Las secciones que no entran en la barra van en el menú del encabezado.
    b.btnMenu.setOnClickListener { mostrarMenu() }

    val ws = b.webModulo.settings
    ws.javaScriptEnabled = true
    ws.domStorageEnabled = true
    ws.loadWithOverviewMode = true
    ws.useWideViewPort = true

    b.webModulo.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            // La web espera el JWT en localStorage('loteria_token'); se lo pasamos
            // una sola vez y despues recargamos el modulo elegido.
            if (!tokenInyectado && !SessionStore.token.isNullOrBlank()) {
                tokenInyectado = true
                val tk = SessionStore.token!!.replace("'", "\\'")
                view.evaluateJavascript("localStorage.setItem('loteria_token','$tk')", null)
                view.postDelayed({ if (modulo != Modulo.SORTEOS) cargarModulo(modulo) }, 200)
            }
        }
    }
    prepararVoz()
    b.webModulo.loadUrl(BuildConfig.LOTERIA_URL)
}

// El WebView de Android no trae Web Speech API: la voz de la bolilla del bingo
// se delega al TextToSpeech del sistema a traves de esta interfaz JS.
private var tts: TextToSpeech? = null
// Impresora térmica Sunmi V2: mismo bridge y AIDL que POSMaster.
private var impresora: PrinterBridge? = null
private var ttsListo = false

private fun prepararVoz() {
    tts = TextToSpeech(this) { estado ->
        if (estado == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "CR")
            ttsListo = true
        }
    }
    b.webModulo.addJavascriptInterface(
        object {
            @JavascriptInterface
            fun hablar(texto: String, pitch: Double, rate: Double) {
                val motor = tts ?: return
                if (!ttsListo) return
                motor.language = Locale("es", "CR")
                motor.setPitch(pitch.toFloat())
                motor.setSpeechRate(rate.toFloat())
                motor.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "bolilla")
            }

            @JavascriptInterface
            fun callar() {
                tts?.stop()
            }
        },
        "LottoVoz",
    )
}

// Impresión térmica: el WebView expone `window.AndroidPOS`, el mismo contrato
// que usa POSMaster. El HTML del comprobante lo genera el backend Go; acá sólo
// se renderiza y se manda a la Sunmi (384 px útiles a 203 dpi).
private fun prepararImpresora() {
    val bridge = PrinterBridge(this)
    bridge.onResult = { payload ->
        val js = "window._pmPrintDispatch && window._pmPrintDispatch(" + JSONObject.quote(payload) + ")"
        b.webModulo.post { b.webModulo.evaluateJavascript(js, null) }
        runOnUiThread {
            try {
                val r = JSONObject(JSONObject(payload).getString("result"))
                val ok = r.optBoolean("ok", false)
                val detalle = if (ok) "Tique impreso" else (r.optString("error").ifBlank { r.optString("message") })
                Toast.makeText(this@HomeActivity, detalle.ifBlank { "No se pudo imprimir" }, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "No se pudo imprimir", Toast.LENGTH_LONG).show()
            }
        }
    }
    bridge.bind()
    impresora = bridge
    b.webModulo.addJavascriptInterface(object {
        @JavascriptInterface
        fun printHtml(payload: String) {
            impresora?.printHtml(payload)
        }

        @JavascriptInterface
        fun printReceipt(payload: String): String {
            return impresora?.printReceipt(payload) ?: "{\"ok\":false,\"error\":\"impresora no disponible\"}"
        }

        @JavascriptInterface
        fun getPrinterStatus(): String {
            return impresora?.getPrinterStatus() ?: "{}"
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return impresora?.deviceInfo() ?: "{}"
        }

        @JavascriptInterface
        fun showToast(mensaje: String) {
            runOnUiThread { Toast.makeText(this@HomeActivity, mensaje, Toast.LENGTH_SHORT).show() }
        }
    }, "AndroidPOS")
}

private fun pedirSaldo() {
    ApiClient.api.me().enqueue(object : Callback<JsonObject> {
        override fun onResponse(call: Call<JsonObject>, res: Response<JsonObject>) {
            if (res.isSuccessful) {
                SessionStore.saldo = num(res.body()!!, "saldo")
                pintarSaldo()
            }
        }
        override fun onFailure(call: Call<JsonObject>, t: Throwable) { /* se reintenta al proximo ciclo */ }
    })
}

private fun seleccionar(nuevo: Modulo) {
    modulo = nuevo
    b.contenidoSorteos.visibility = if (nuevo == Modulo.SORTEOS) View.VISIBLE else View.GONE
    b.webModulo.visibility = if (nuevo == Modulo.SORTEOS) View.GONE else View.VISIBLE
    val activo = getColor(R.color.lot_accent)
    val inactivo = getColor(R.color.lot_text_muted)
    b.tabSorteos.setTextColor(if (nuevo == Modulo.SORTEOS) activo else inactivo)
    b.tabLoterias.setTextColor(if (nuevo == Modulo.LOTERIAS) activo else inactivo)
    b.tabQuinielas.setTextColor(if (nuevo == Modulo.QUINIELAS) activo else inactivo)
    b.tabRifas.setTextColor(if (nuevo == Modulo.RIFAS) activo else inactivo)
    b.tabCasino.setTextColor(if (nuevo == Modulo.CASINO) activo else inactivo)
    if (nuevo != Modulo.SORTEOS) cargarModulo(nuevo) else pedirSaldo()
}

/** Secciones completas de la web que no caben en la barra inferior. */
private fun mostrarMenu() {
    val opciones = arrayOf(
        "Mis apuestas" to Modulo.MIS_APUESTAS,
        "Resultados" to Modulo.RESULTADOS,
        "Reportes" to Modulo.REPORTES,
        "Mi perfil" to Modulo.PERFIL,
    )
    AlertDialog.Builder(this)
        .setTitle("Secciones")
        .setItems(opciones.map { it.first }.toTypedArray()) { _, which ->
            seleccionar(opciones[which].second)
        }
        .setNegativeButton("Cerrar", null)
        .show()
}

private fun cargarModulo(nuevo: Modulo) {
    val base = BuildConfig.LOTERIA_URL.trimEnd('/')
    val ruta = when (nuevo) {
        Modulo.LOTERIAS -> "#/loterias"
        Modulo.QUINIELAS -> "#/quinielas"
        Modulo.RIFAS -> "#/rifas"
        Modulo.CASINO -> "#/casino"
        Modulo.MIS_APUESTAS -> "#/mis-apuestas"
        Modulo.RESULTADOS -> "#/resultados"
        Modulo.REPORTES -> "#/reportes"
        Modulo.PERFIL -> "#/perfil"
        Modulo.SORTEOS -> "#/dashboard"
    }
    val tk = SessionStore.token?.replace("'", "\\'") ?: ""
    // `app=1` le dice a la web que corra en modo app: sin sidebar ni topbar
    // propios (la app ya los pone) y con el lobby a todo ancho.
    b.webModulo.loadUrl("$base/?token=$tk&app=1$ruta")
}
}
