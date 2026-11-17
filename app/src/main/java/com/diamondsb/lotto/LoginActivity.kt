package com.diamondsb.lotto

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.diamondsb.lotto.databinding.ActivityLoginBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Login nativo. El backend devuelve el token JWT y los datos del usuario.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var updater: Updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        updater = Updater(this, BuildConfig.LOTERIA_URL)

        if (SessionStore.isLoggedIn) {
            irAHome()
            return
        }

        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnEntrar.setOnClickListener { entrar() }
        b.tvRecuperar.setOnClickListener {
            Toast.makeText(this, "Pedile la clave a tu punto o al admin", Toast.LENGTH_SHORT).show()
        }

        // Revisa si hay una versión más nueva publicada en la web (descargar/version.json).
        updater.check()
    }

    override fun onDestroy() {
        updater.unregister()
        super.onDestroy()
    }

    private fun entrar() {
        val email = b.etEmail.text.toString().trim()
        val pass = b.etPass.text.toString()
        if (email.isEmpty() || pass.isEmpty()) {
            b.etEmail.error = if (email.isEmpty()) "Tu correo" else null
            b.etPass.error = if (pass.isEmpty()) "Tu clave" else null
            return
        }

        b.btnEntrar.isEnabled = false
        b.progreso.visibility = View.VISIBLE

        ApiClient.api.login(LoginRequest(email, pass)).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, res: Response<LoginResponse>) {
                b.progreso.visibility = View.GONE
                b.btnEntrar.isEnabled = true
                if (!res.isSuccessful() || res.body()?.token.isNullOrBlank()) {
                    Toast.makeText(
                        this@LoginActivity,
                        res.body()?.let { null } ?: mensajeError(res.code()),
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }
                guardarSesion(res.body()!!)
                irAHome()
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                b.progreso.visibility = View.GONE
                b.btnEntrar.isEnabled = true
                Toast.makeText(this@LoginActivity, "Sin conexion con el servidor", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun mensajeError(code: Int): String = when (code) {
        401 -> "Correo o clave incorrectos"
        else -> "No se pudo entrar (codigo $code)"
    }

    private fun guardarSesion(r: LoginResponse) {
        SessionStore.token = r.token
        val u = r.user
        if (u != null && !u.isJsonNull) {
            SessionStore.nombre = u.get("nombre")?.takeIf { !it.isJsonNull }?.asString ?: ""
            SessionStore.email = u.get("email")?.takeIf { !it.isJsonNull }?.asString ?: ""
            SessionStore.rol = u.get("rol")?.takeIf { !it.isJsonNull }?.asString ?: "cliente"
            SessionStore.saldo = u.get("saldo")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
        }
    }

    private fun irAHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
