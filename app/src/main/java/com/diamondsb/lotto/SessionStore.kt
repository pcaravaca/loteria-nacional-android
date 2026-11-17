package com.diamondsb.lotto

import android.content.Context
import android.content.SharedPreferences

/** Sesion y datos del usuario en almacenamiento local. */
object SessionStore {

    private const val PREFS = "lotto_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_NOMBRE = "nombre"
    private const val KEY_EMAIL = "email"
    private const val KEY_ROL = "rol"
    private const val KEY_SALDO = "saldo"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_TOKEN, value).apply() }

    var nombre: String
        get() = prefs.getString(KEY_NOMBRE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_NOMBRE, value).apply() }

    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_EMAIL, value).apply() }

    var rol: String
        get() = prefs.getString(KEY_ROL, "cliente") ?: "cliente"
        set(value) { prefs.edit().putString(KEY_ROL, value).apply() }

    var saldo: Double
        get() = prefs.getFloat(KEY_SALDO, 0f).toDouble()
        set(value) { prefs.edit().putFloat(KEY_SALDO, value.toFloat()).apply() }

    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    fun clear() = prefs.edit().clear().apply()
}
