package com.zxsofa.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Otro dispositivo con ZX Sofá esperando juegos en la red local. */
data class PeerDevice(val name: String, val host: String, val port: Int) {
    val address: String get() = "$host:$port"
}

/**
 * Anuncia este dispositivo en la red local (mientras la pantalla «Desde otro
 * dispositivo» está abierta) y busca otros, para enviar la colección sin teclear IPs.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "ZX Sofá · ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    /** Busca dispositivos; [onChange] recibe la lista completa cada vez que cambia (en el hilo principal). */
    fun discover(onChange: (List<PeerDevice>) -> Unit) {
        val found = linkedMapOf<String, PeerDevice>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    pending.add(info)
                    resolveNext(found, onChange)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    found.remove(info.serviceName)
                    onChange(found.values.toList())
                }
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    // NsdManager solo resuelve un servicio a la vez: los encolamos
    @Suppress("DEPRECATION")
    private fun resolveNext(found: MutableMap<String, PeerDevice>, onChange: (List<PeerDevice>) -> Unit) {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext(found, onChange) }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        val host = info.host?.hostAddress
                        if (host != null && !host.contains(':')) {
                            found[info.serviceName] = PeerDevice(info.serviceName, host, info.port)
                            onChange(found.values.toList())
                        }
                        resolving = false
                        resolveNext(found, onChange)
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
    }

    companion object {
        const val SERVICE_TYPE = "_zxsofa._tcp."
    }
}

/** Envía un fichero a otro ZX Sofá (que tenga abierta la pantalla «Desde otro dispositivo»). */
suspend fun sendFileTo(address: String, file: File, name: String, onProgress: (Float) -> Unit): String =
    withContext(Dispatchers.IO) {
        val base = if (address.startsWith("http")) address.trimEnd('/') else "http://${address.trim().trimEnd('/')}"
        val withPort = if (Regex(":\\d+$").containsMatchIn(base.removePrefix("http://"))) base else "$base:8080"
        val conn = URL("$withPort/upload?name=${URLEncoder.encode(name, "UTF-8")}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setFixedLengthStreamingMode(file.length())
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            val total = file.length().coerceAtLeast(1)
            var sent = 0L
            conn.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent.toFloat() / total)
                    }
                }
            }
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) } ?: ""
            if (code != 200) throw IllegalStateException(body.ifBlank { "Error $code" })
            body
        } finally {
            conn.disconnect()
        }
    }
