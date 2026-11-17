package com.diamondsb.lotto

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-update de la app nativa.
 *
 * Consulta `<base>/descargar/version.json`, que publica el frontend web, y si el versionCode
 * remoto supera al instalado ofrece descargar e instalar el APK.
 *
 * JSON esperado:
 * {"versionCode":2,"versionName":"0.2.0","url":"http://host/descargar/LoteriaNacional.apk"}
 */
class Updater(
    private val activity: Activity,
    private val baseUrl: String,
) {
    private var downloadId: Long = -1
    private var registered = false

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(downloadId)
            if (uri != null) install(uri) else toast("No se pudo descargar la actualización")
        }
    }

    /** Revisa si hay versión nueva. Silencioso si no hay red o no hay publicación. */
    fun check() {
        Thread {
            try {
                val conn = URL("${baseUrl.trimEnd('/')}/descargar/version.json")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val remote = json.getInt("versionCode")
                val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                // longVersionCode es API 28+; los Sunmi V2 van por API 25.
                val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                if (remote > current) {
                    val name = json.optString("versionName", remote.toString())
                    val apk = json.optString("url", "${baseUrl.trimEnd('/')}/descargar/LoteriaNacional.apk")
                    activity.runOnUiThread { promptUpdate(name, apk) }
                } else {
                    Log.d(TAG, "sin actualizacion: local=$current remota=$remote")
                }
            } catch (e: Throwable) {
                Log.d(TAG, "check fallo: ${e.message}")
            }
        }.start()
    }

    private fun promptUpdate(versionName: String, apkUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle("Nueva versión disponible")
            .setMessage("La versión $versionName está lista. ¿Actualizar ahora?")
            .setPositiveButton("Actualizar") { _, _ -> download(apkUrl) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun download(apkUrl: String) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Lotería Nacional")
            .setDescription("Descargando actualización")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "loteria-update.apk")
        downloadId = dm.enqueue(request)
        try {
            activity.registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            registered = true
        } catch (_: Throwable) {
        }
        toast("Descargando actualización…")
    }

    private fun install(uri: Uri) {
        // API 26+ exige habilitar "instalar apps desconocidas" para esta app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast("Habilitá 'Instalar apps desconocidas' para Lotería Nacional y repetí la actualización")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Throwable) {
            toast("No se pudo abrir el instalador: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    fun unregister() {
        if (registered) {
            try {
                activity.unregisterReceiver(onDownloadComplete)
            } catch (_: Throwable) {
            }
            registered = false
        }
    }

    private companion object {
        const val TAG = "Updater"
    }
}
