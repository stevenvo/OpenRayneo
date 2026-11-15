// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal data class SavedScript(val id: String, val title: String, val text: String)
internal class ScriptLibrary(context: Context) {
    private val preferences = context.getSharedPreferences("script_library", Context.MODE_PRIVATE)
    fun read(): List<SavedScript> {
        val items = JSONArray(preferences.getString("scripts", "[]"))
        return (0 until items.length()).map { val item = items.getJSONObject(it)
            SavedScript(item.getString("id"), item.getString("title"), item.getString("text")) }
    }
    fun save(values: List<SavedScript>) {
        require(values.size <= 30) { "The local library holds up to 30 scripts. Delete one first." }
        val items = JSONArray()
        values.forEach { items.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text)) }
        check(preferences.edit().putString("scripts", items.toString()).commit()) { "Could not save the script library." }
    }
}

@Composable
internal fun FeaturesScreen(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { ScriptLibrary(context) }
    var scripts by remember { mutableStateOf(runCatching { library.read() }.getOrDefault(emptyList())) }
    var selected by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableFloatStateOf(120f) }
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(exportText) }
            } }
            result.onSuccess { model.notice = "Transcript exported" }.onFailure { model.error = "Could not export the transcript." }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readNBytes(ScriptTransfer.MAX_BYTES + 1) }
                require(bytes.size <= ScriptTransfer.MAX_BYTES) { "Use a UTF-8 text file of at most 32,000 bytes." }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } }
            result.onSuccess { text = it; selected = ""; title = "Imported script" }
                .onFailure { model.error = "Could not import this file. Use UTF-8 plain text, up to 32,000 bytes." }
        }
    }
    val s = model.state
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Glasses home", style = MaterialTheme.typography.titleLarge)
        Text("The stock home screen stays in the glasses. Choose a feature here to open it; return home to end the companion display and microphone session.")
        OutlinedButton(onClick = { model.attempt { model.service().returnHome() } }, enabled = s.available) { Text("Return to glasses home") }
        HorizontalDivider()
        Text("Live transcription", style = MaterialTheme.typography.titleLarge)
        Text("Continuous English captions use the glasses microphone and run offline on this phone. No caption audio or text is sent to an AI service. Text stays in memory unless you export it.")
        Text(s.captions.detail)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.attempt { model.service().startCaptions() } }, enabled = s.available && !s.captions.active && !s.teleprompter.active) { Text("Start captions") }
            OutlinedButton(onClick = { model.attempt { model.service().returnHome() } }, enabled = s.captions.active) { Text("Stop") }
        }
        val transcript = listOf(s.captions.transcript, s.captions.partial).filter { it.isNotBlank() }.joinToString("\n")
        if (transcript.isNotBlank()) {
            Text(transcript.takeLast(6000))
            OutlinedButton(onClick = { exportText = transcript; export.launch("OpenRayneo-transcript.txt") }) { Text("Export transcript") }
        }
        HorizontalDivider()
        Text("Teleprompter", style = MaterialTheme.typography.titleLarge)
        Text("Save an English script, then send it to the glasses. Use the crown to start, pause and scroll. Auto-scroll speed is adjustable below. This local library does not sync with the official app.")
        Text(s.teleprompter.detail)
        OutlinedTextField(title, { title = it.take(80) }, label = { Text("Script title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(text, { if (it.toByteArray().size <= ScriptTransfer.MAX_BYTES) text = it }, label = { Text("Script text") }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12)
        Text("Scroll speed: ${speed.toInt()}")
        Slider(speed, { speed = it }, valueRange = 30f..300f, steps = 26)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { import.launch(arrayOf("text/plain")) }) { Text("Import TXT") }
            OutlinedButton(onClick = { model.attempt {
                require(text.isNotBlank()) { "Enter a script first." }
                val id = selected.ifBlank { UUID.randomUUID().toString() }
                val entry = SavedScript(id, title.ifBlank { "Untitled" }, text)
                val next = listOf(entry) + scripts.filter { it.id != id }
                library.save(next); scripts = next; selected = id; model.notice = "Script saved on this phone"
            } }) { Text("Save script") }
        }
        Button(onClick = { model.attempt { model.service().startTeleprompter(title, text, speed.toInt()) } }, enabled = s.available && !s.teleprompter.active && text.isNotBlank()) { Text("Play on glasses") }
        if (s.teleprompter.active) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.attempt { model.service().controlTeleprompter("pause") } }, enabled = !s.teleprompter.transferring) { Text("Pause") }
            OutlinedButton(onClick = { model.attempt { model.service().controlTeleprompter("resume") } }, enabled = !s.teleprompter.transferring) { Text("Resume") }
            OutlinedButton(onClick = { model.attempt { model.service().returnHome() } }) { Text("Stop") }
        }
        TextButton(onClick = { selected = ""; title = ""; text = "" }) { Text("New script") }
        scripts.forEach { script ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(script.title, style = MaterialTheme.typography.titleMedium)
                    Text(script.text.take(100))
                    Row {
                        TextButton(onClick = { selected = script.id; title = script.title; text = script.text }) { Text("Open") }
                        TextButton(onClick = { model.attempt {
                            val next = scripts.filter { it.id != script.id }; library.save(next); scripts = next
                            if (selected == script.id) selected = ""
                        } }) { Text("Delete saved copy") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
