package com.diamondsb.lotto

import android.content.Intent
import android.os.Bundle
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

        cargar()
    }

    override fun onResume() {
        super.onResume()
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
}
