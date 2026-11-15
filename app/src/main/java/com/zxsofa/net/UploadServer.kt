package com.zxsofa.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Servidor web mínimo para enviar juegos a la app desde el navegador de otro
 * dispositivo de la misma red (pensado para Android TV, que no suele tener
 * selector de archivos). Solo funciona mientras la pantalla está abierta.
 */
class UploadServer(
    private val tempDir: File,
    /** Recibe el nombre y el fichero recibido (temporal); devuelve el texto a mostrar en el navegador. */
    private val onFile: (name: String, file: File) -> String,
    /** Genera la copia de seguridad de la colección para descargarla. */
    private val onBackup: () -> File?,
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    var port: Int = 0
        private set

    fun start(): Boolean {
        for (p in 8080..8090) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: Exception) {
                // puerto ocupado: probamos el siguiente
            }
        }
        val s = server ?: return false
        running = true
        Thread({
            while (running) {
                val client = try { s.accept() } catch (e: Exception) { break }
                Thread({ handle(client) }, "ZXSofa-http-client").start()
            }
        }, "ZXSofa-http").start()
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "GET" }
                val target = parts.getOrElse(1) { "/" }
                val path = target.substringBefore('?')
                val params = target.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        respond(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
                    method == "POST" && path == "/upload" -> {
                        val len = headers["content-length"]?.toLongOrNull() ?: -1L
                        if (len <= 0 || len > MAX_UPLOAD) {
                            respond(out, 413, "text/plain; charset=utf-8", "Fichero vacío o demasiado grande".toByteArray())
                            return
                        }
                        val name = params["name"]?.ifBlank { null } ?: "juego.tap"
                        val tmp = File.createTempFile("upload", ".bin", tempDir)
                        try {
                            if (!copyBody(input, len, tmp)) {
                                respond(out, 400, "text/plain; charset=utf-8", "Transferencia incompleta".toByteArray())
                                return
                            }
                            val msg = onFile(name, tmp)
                            respond(out, 200, "text/plain; charset=utf-8", msg.toByteArray())
                        } finally {
                            tmp.delete()
                        }
                    }
                    method == "GET" && path == "/backup.zip" -> {
                        val f = onBackup()
                        if (f == null || !f.exists()) {
                            respond(out, 500, "text/plain; charset=utf-8", "No se pudo crear la copia".toByteArray())
                        } else {
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${f.length()}\r\n" +
                                "Content-Disposition: attachment; filename=\"coleccion-zxsofa.zip\"\r\nConnection: close\r\n\r\n").toByteArray())
                            f.inputStream().use { it.copyTo(out) }
                            out.flush()
                        }
                    }
                    else -> respond(out, 404, "text/plain", "No encontrado".toByteArray())
                }
            } catch (e: Exception) {
                Log.w("ZXSofa", "Error HTTP", e)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun copyBody(input: InputStream, len: Long, dest: File): Boolean {
        val buf = ByteArray(64 * 1024)
        var left = len
        dest.outputStream().use { o ->
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                o.write(buf, 0, n)
                left -= n
            }
        }
        return left == 0L
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        const val MAX_UPLOAD = 2L * 1024 * 1024 * 1024

        /** Direcciones IPv4 locales del dispositivo (Wi-Fi / Ethernet). */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }

        private val PAGE = """
<!DOCTYPE html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZX Sofá · Enviar juegos</title>
<style>
 body{font-family:system-ui,sans-serif;background:#0e0e10;color:#eee;max-width:640px;margin:0 auto;padding:24px}
 h1{margin:0 0 4px}.stripes{display:flex;gap:0;margin-bottom:20px}.stripes i{width:24px;height:10px;display:block}
 .drop{border:3px dashed #00c8e8;border-radius:14px;padding:40px 16px;text-align:center;cursor:pointer}
 .drop.over{background:#00c8e822} button{font-size:18px;padding:12px 20px;border-radius:10px;border:0;background:#00c8e8;color:#000}
 li{margin:6px 0} .ok{color:#7f7} .err{color:#f77}
</style></head><body>
<h1>ZX Sofá</h1>
<div class="stripes"><i style="background:#d80000"></i><i style="background:#f2d000"></i><i style="background:#00c000"></i><i style="background:#00c8e8"></i></div>
<p>Elige o arrastra juegos de Spectrum (.tap, .tzx, .z80, .sna, .dsk, .zip…). Aparecerán en la tele al momento.</p>
<label class="drop" id="drop"><input type="file" id="files" multiple hidden><p>Toca aquí para elegir archivos<br>o arrástralos encima</p><button type="button" onclick="document.getElementById('files').click()">Elegir juegos</button></label>
<ul id="log"></ul>
<p style="margin-top:32px"><a style="color:#f2d000" href="/backup.zip">⬇ Descargar copia de seguridad de esta colección</a><br>
<small>Juegos, carátulas, fichas y partidas en un .zip. Para restaurarla, súbela aquí o añádela con el botón + de la app.</small></p>
<script>
const log=document.getElementById('log'),drop=document.getElementById('drop');
async function send(files){for(const f of files){const li=document.createElement('li');li.textContent=f.name+'…';log.prepend(li);
 try{const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});const t=await r.text();li.textContent=f.name+': '+t;li.className=r.ok?'ok':'err';}
 catch(e){li.textContent=f.name+': error de conexión';li.className='err';}}}
document.getElementById('files').onchange=e=>send(e.target.files);
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');send(e.dataTransfer.files)};
</script></body></html>
""".trimIndent()
    }
}
