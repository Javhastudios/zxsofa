package com.zxsofa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxsofa.data.GameRepository
import com.zxsofa.net.Discovery
import com.zxsofa.net.UploadServer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.runBlocking

@Composable
fun WifiImportScreen(vm: LibraryViewModel) {
    var port by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val received = remember { mutableStateListOf<String>() }
    val addresses = remember { UploadServer.localAddresses() }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val server = UploadServer(context.cacheDir, onFile = { name, file ->
            // Hilo del servidor: importamos de forma síncrona y avisamos a la interfaz
            val result = runBlocking { vm.repo.importFile(name, file) }
            vm.onReceived(result)
            val text = when (result) {
                is GameRepository.ImportResult.Backup -> "colección recibida: ${result.added} juegos nuevos, ${result.updated} completados" +
                    if (result.skipped > 0) ", ${result.skipped} sin cambios" else ""
                is GameRepository.ImportResult.MagazineAdded -> "revista añadida ✔ (${result.magazine.pages} páginas)"
                is GameRepository.ImportResult.PokAttached -> "POKEs añadidos a ${result.game.title} ✔"
                is GameRepository.ImportResult.Added -> "añadido ✔"
                is GameRepository.ImportResult.Duplicate -> "ya estaba en la colección"
                is GameRepository.ImportResult.AddedSide -> "añadido como otra cara de ${result.game.title} ✔"
                is GameRepository.ImportResult.Error -> result.message
            }
            received.add(0, "$name: $text")
            text
        }, onBackup = { mags -> runBlocking { vm.repo.exportBackupToFile(mags) } })
        val discovery = Discovery(context)
        if (server.start()) {
            port = server.port
            discovery.advertise(server.port)
        } else failed = true
        onDispose {
            discovery.stop()
            server.stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Enviar juegos por Wi-Fi") {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                failed -> Text("No se pudo abrir el servidor.", color = MaterialTheme.colorScheme.error)
                addresses.isEmpty() -> Text("No hay conexión Wi-Fi o Ethernet.", color = MaterialTheme.colorScheme.error)
                port == 0 -> Text("Arrancando…")
                else -> {
                    Text("Desde el móvil o el ordenador, conectado a la misma red, abre en el navegador:")
                    addresses.forEach { ip ->
                        Text(
                            "http://$ip:$port",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            color = ZxColors.Yellow,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ZxColors.SurfaceHigh).padding(16.dp),
                        )
                    }
                    Text(
                        "Elige allí los juegos y aparecerán en tu colección. Deja esta pantalla abierta mientras los envías.\n\n" +
                            "Para pasar la colección entera desde otro móvil o tablet con ZX Sofá: allí entra en Ajustes → «Enviar colección a otro dispositivo». " +
                            "Esta pantalla también ofrece en esa página web un enlace para descargar una copia de seguridad.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (received.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Recibidos")
                received.forEach { Text(it) }
            }
        }
    }
}
