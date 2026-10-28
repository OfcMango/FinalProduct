package com.neo.TouchTalkle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

private val Ink = Color(0xFF101827)
private val Panel = Color(0xFF182337)
private val Panel2 = Color(0xFF202F46)
private val Mint = Color(0xFF71F0C0)
private val Sky = Color(0xFF86B8FF)
private val Muted = Color(0xFFA7B4C8)
private val SppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onReceive: ((String) -> Unit)? = null
    private var onStatus: ((String, Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Mint, onPrimary = Ink, background = Ink, onBackground = Color.White,
                surface = Panel, onSurface = Color.White, secondary = Sky
            )) {
                TouchTalkScreen(
                    connected = { socket?.isConnected == true },
                    connect = { chooseDevice() },
                    sendLine = { sendBluetoothLine(it) },
                    speak = { say(it) },
                    onBindCallbacks = { receive, status, error ->
                        onReceive = receive; onStatus = status; onError = error
                    }
                )
            }
        }
    }

    private fun say(text: String) {
        if (ttsReady && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hb-${System.currentTimeMillis()}")
    }

    @SuppressLint("MissingPermission")
    private fun chooseDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 410)
            onStatus?.invoke("Allow Bluetooth permission, then tap Connect again", false)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { onError?.invoke("This phone does not support Bluetooth"); return }
        if (!adapter.isEnabled) { onError?.invoke("Turn on Bluetooth in Android settings first"); return }
        val devices = try { adapter.bondedDevices?.toList().orEmpty() } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            onError?.invoke("No paired devices. Pair your ESP32 in Android Bluetooth settings, then return here.")
            return
        }
        runOnUiThread {
            val labels = devices.joinToString("\n") { "${it.name ?: "Unknown device"} — ${it.address}" }
            android.app.AlertDialog.Builder(this)
                .setTitle("Choose paired ESP32")
                .setItems(labels.split("\n").toTypedArray()) { _, which -> connectDevice(devices[which]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        onStatus?.invoke("Connecting to ${device.name ?: "ESP32"}…", false)
        thread(name = "BluetoothConnect") {
            try {
                try { socket?.close() } catch (_: Exception) {}
                val candidate = device.createRfcommSocketToServiceRecord(SppUuid)
                candidate.connect()
                socket = candidate
                output = candidate.outputStream
                onStatus?.let { cb -> runOnUiThread { cb("Connected to ${device.name ?: "ESP32"}", true) } }
                val reader = BufferedReader(InputStreamReader(candidate.inputStream, Charsets.UTF_8))
                while (candidate.isConnected) {
                    val line = reader.readLine() ?: break
                    val value = line.trimEnd('\r')
                    runOnUiThread { onReceive?.invoke(value) }
                }
                runOnUiThread { onStatus?.invoke("Bluetooth disconnected", false) }
            } catch (e: Exception) {
                runOnUiThread { onError?.invoke("Connection failed: ${e.message ?: "check pairing and ESP32 firmware"}") }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null; output = null
                runOnUiThread { onStatus?.invoke("Not connected", false) }
            }
        }
    }

    private fun sendBluetoothLine(line: String): Boolean {
        return try {
            val stream = output ?: return false
            synchronized(stream) { stream.write((line.trimEnd() + "\n").toByteArray(Charsets.UTF_8)); stream.flush() }
            true
        } catch (e: Exception) {
            onError?.invoke("Could not send to ESP32: ${e.message ?: "Bluetooth error"}")
            false
        }
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun TouchTalkScreen(
    connected: () -> Boolean,
    connect: () -> Unit,
    sendLine: (String) -> Boolean,
    speak: (String) -> Unit,
    onBindCallbacks: (((String) -> Unit, (String, Boolean) -> Unit, (String) -> Unit) -> Unit)
) {
    var activeTab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Not connected") }
    var isConnected by remember { mutableStateOf(false) }
    var autoSpeak by remember { mutableStateOf(true) }
    var incoming by remember { mutableStateOf("") }
    var latestWord by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var speechText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf("") }
    var sentCount by remember { mutableIntStateOf(0) }

    onBindCallbacks({ line ->
        when {
            line.startsWith("DRAFT:") -> draft = line.removePrefix("DRAFT:")
            line.startsWith("TEXT:") -> {
                val word = line.removePrefix("TEXT:")
                latestWord = word
                incoming = (incoming + word + " ").takeLast(3000)
                if (autoSpeak && word.isNotBlank()) speak(word)
            }
            line.startsWith("STATUS:") -> status = line.removePrefix("STATUS:")
            else -> {
                incoming = (incoming + line + "\n").takeLast(3000)
                if (autoSpeak && line.isNotBlank()) speak(line)
            }
        }
    }, { s, ok -> status = s; isConnected = ok }, { alert = it })

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!recognized.isNullOrBlank()) speechText = (speechText + " " + recognized).trim()
        else if (result.resultCode == android.app.Activity.RESULT_OK) alert = "No speech was recognized. Try again in a quieter place."
    }

    if (alert.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { alert = "" },
            title = { Text("TouchTalk") },
            text = { Text(alert) },
            confirmButton = { TextButton(onClick = { alert = "" }) { Text("OK") } },
            containerColor = Panel
        )
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101827), Color(0xFF0D1725))))
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Brush.linearGradient(listOf(Mint, Sky)), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Ink, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TouchTalk", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("TWO-WAY COMMUNICATION", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(if (isConnected) Color(0xFF153C34) else Panel2, CircleShape).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Box(Modifier.size(7.dp).background(if (isConnected) Mint else Muted, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(if (isConnected) "ONLINE" else "OFFLINE", fontSize = 10.sp, color = if (isConnected) Mint else Muted, fontWeight = FontWeight.Bold)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Panel2, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Sky)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Bluetooth Classic · ESP32 SPP", color = Muted, fontSize = 11.sp)
                }
                Button(onClick = connect, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), shape = RoundedCornerShape(13.dp)) {
                    Text(if (isConnected) "Reconnect" else "Connect", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TabPill("Receive & speak", activeTab == 0, Modifier.weight(1f)) { activeTab = 0 }
            TabPill("Speak → Braille", activeTab == 1, Modifier.weight(1f)) { activeTab = 1 }
        }
        Spacer(Modifier.height(16.dp))

        if (activeTab == 0) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Incoming messages", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Text received from your Braille keypad", fontSize = 12.sp, color = Muted)
                    }
                    IconButton(onClick = { incoming = ""; latestWord = "" }) { Icon(Icons.Default.DeleteOutline, "Clear messages", tint = Muted) }
                }
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE TRANSCRIPT", color = Mint, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Mint, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(15.dp))
                        Text(incoming.ifBlank { "Your received text will appear here…" }, color = if (incoming.isBlank()) Muted else Color.White, fontSize = 19.sp, lineHeight = 29.sp)
                        if (draft.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("KEYBOARD DRAFT", color = Sky, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                            Text(draft, color = Sky, fontSize = 16.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel2.copy(alpha = 0.65f)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(Color(0xFF29433F), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Mint)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Read messages aloud", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Use your phone's voice", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = autoSpeak, onCheckedChange = { autoSpeak = it })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { if (latestWord.isNotBlank()) speak(latestWord) else if (incoming.isNotBlank()) speak(incoming) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("Read latest message")
                }
                Spacer(Modifier.height(16.dp))
                Text("TIP  Pair your ESP32 in Android Bluetooth settings before connecting.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Speak a message", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Your words become six-dot Braille signals", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("YOUR MESSAGE", color = Mint, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("${speechText.length} chars", color = Muted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = speechText, onValueChange = { speechText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 125.dp),
                            placeholder = { Text("Tap the microphone and speak…", color = Muted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Mint, unfocusedBorderColor = Panel2,
                                cursorColor = Mint, focusedContainerColor = Color(0xFF111D2D), unfocusedContainerColor = Color(0xFF111D2D)
                            ), shape = RoundedCornerShape(16.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message for Braille")
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        }
                        Button(onClick = {
                            try { speechLauncher.launch(speechIntent) }
                            catch (_: Exception) { alert = "Speech recognition is unavailable. Check that a speech service is installed on your phone." }
                        }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                            Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Tap to speak", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("BRAILLE PREVIEW", color = Sky, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        val preview = speechText.take(24).map { it to braillePattern(it) }
                        if (preview.isEmpty()) Text("Letters and active dots will preview here.", color = Muted, fontSize = 12.sp)
                        else LazyColumn(Modifier.heightIn(max = 130.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(preview) { (character, mask) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (character == ' ') "SPACE" else character.toString(), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                                    BrailleDots(mask)
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (character == ' ') "clear cell" else "dots ${dotNumbers(mask)}", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cell delay", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text("650 ms", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Each character is sent as CELL:<dot-mask>. Adjust the delay in firmware/app code for your actuator speed.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    enabled = isConnected && speechText.isNotBlank() && !isSending,
                    onClick = {
                        isSending = true
                        val message = speechText
                        thread(name = "BrailleTransmit") {
                            var ok = true
                            var sentNow = 0
                            for (ch in message) {
                                val patterns = brailleSequence(ch)
                                for (mask in patterns) {
                                    if (!sendLine("CELL:$mask")) { ok = false; break }
                                    Thread.sleep(650)
                                }
                                if (!ok) break
                                sentNow++
                            }
                            if (ok) sendLine("DONE")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                sentCount += sentNow
                                isSending = false
                                alert = if (ok) "Message sent to ESP32. Sent $sentNow character(s)." else "Transmission stopped. Check the Bluetooth connection."
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
                    else Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(9.dp)); Text(if (isSending) "Sending Braille signals…" else "Send to Braille pins", fontWeight = FontWeight.Bold)
                }
                if (!isConnected) Text("Connect to your ESP32 first to enable sending.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
                Text("Numbers use the Braille number sign. Capital letters use the capital sign. This first version uses uncontracted English Braille.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Mint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("NEO LABS  ·  TOUCHTALK", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), color = if (selected) Panel2 else Color.Transparent) {
        Text(label, modifier = Modifier.padding(vertical = 13.dp), color = if (selected) Color.White else Muted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun BrailleDots(mask: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { (0..2).forEach { i -> Dot((mask and (1 shl i)) != 0) } }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { (3..5).forEach { i -> Dot((mask and (1 shl i)) != 0) } }
    }
}

@Composable
private fun Dot(active: Boolean) {
    Box(Modifier.size(8.dp).background(if (active) Mint else Color(0xFF3B4A60), CircleShape))
}

private fun dotNumbers(mask: Int): String = (1..6).filter { (mask and (1 shl (it - 1))) != 0 }.joinToString().ifBlank { "none" }

// Six-bit masks: bit 0 is dot 1, bit 5 is dot 6.
private val letterMasks = mapOf(
    'a' to 1, 'b' to 3, 'c' to 9, 'd' to 25, 'e' to 17, 'f' to 11, 'g' to 27, 'h' to 19,
    'i' to 10, 'j' to 26, 'k' to 5, 'l' to 7, 'm' to 13, 'n' to 29, 'o' to 21, 'p' to 15,
    'q' to 31, 'r' to 23, 's' to 14, 't' to 30, 'u' to 37, 'v' to 39, 'w' to 58, 'x' to 45,
    'y' to 61, 'z' to 53
)
private val punctuationMasks = mapOf(',' to 2, ';' to 6, ':' to 18, '.' to 50, '!' to 22, '?' to 38, '\'' to 4, '-' to 36)
private val digitLetters = mapOf('1' to 'a', '2' to 'b', '3' to 'c', '4' to 'd', '5' to 'e', '6' to 'f', '7' to 'g', '8' to 'h', '9' to 'i', '0' to 'j')
private fun braillePattern(ch: Char): Int = brailleSequence(ch).lastOrNull() ?: 0
private fun brailleSequence(ch: Char): List<Int> {
    if (ch == ' ') return listOf(0)
    if (ch.isDigit()) return listOf(60, letterMasks[digitLetters[ch]!!] ?: 0)
    if (ch.isUpperCase()) return listOf(32, letterMasks[ch.lowercaseChar()] ?: punctuationMasks[ch] ?: 0)
    return listOf(letterMasks[ch] ?: punctuationMasks[ch] ?: 0)
}
