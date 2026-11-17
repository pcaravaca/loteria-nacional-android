package com.diamondsb.lotto

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP unico de la app. Toda la logica vive en el backend Go de
 * loteria-nacional-v2; aca solo se habla con su API.
 */
object ApiClient {

    val api: LottoApi by lazy {
        val ok = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = SessionStore.token
                val req = if (token.isNullOrBlank()) chain.request() else chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                chain.proceed(req)
            }
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.LOTERIA_URL.trimEnd('/') + "/")
            .client(ok)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LottoApi::class.java)
    }
}
