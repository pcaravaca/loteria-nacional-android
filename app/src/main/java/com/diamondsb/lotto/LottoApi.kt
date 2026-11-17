package com.diamondsb.lotto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * Subconjunto de la API de loteria-nacional-v2 que usa la app nativa.
 * Todo viaja con el Bearer token que entrega /auth/login.
 */
interface LottoApi {

    @POST("api/auth/login")
    fun login(@Body body: LoginRequest): Call<LoginResponse>

    /** Datos del usuario, saldo y bono. */
    @GET("api/auth/me")
    fun me(): Call<JsonObject>

    /** Proximos sorteos abiertos con su loteria (para apostar). */
    @GET("api/sorteos/proximos")
    fun proximosSorteos(): Call<JsonArray>

    /** Loteras activas: reglas (costo, digitos, tope de numeros). */
    @GET("api/loterias")
    fun loterias(): Call<JsonArray>

    /** Apuestas del usuario. */
    @GET("api/apuestas/mias")
    fun misApuestas(): Call<JsonArray>

    @POST("api/apuestas")
    fun crearApuesta(@Body body: JsonObject): Call<JsonObject>

    /** Limites voluntarios de gasto (juego responsable). */
    @GET("api/cuenta/limites")
    fun limites(): Call<JsonObject>

    @PUT("api/cuenta/limites")
    fun guardarLimites(@Body body: JsonObject): Call<JsonObject>
}

data class LoginRequest(val email: String, val password: String)

data class LoginResponse(
    val token: String?,
    val user: JsonObject?,
)
