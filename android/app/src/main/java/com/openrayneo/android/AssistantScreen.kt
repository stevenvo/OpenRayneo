// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantScreen(model: AppModel, actions: UiActions, configure: (String) -> Unit) {
    var mobile by rememberSaveable { mutableStateOf(true) }
    val source = if (mobile) AnswerSource.MOBILE_AGENT else AnswerSource.COMPUTER_CODEX
    val s = model.state
    val running = if (mobile) s.phoneRunning else s.computerRunning
    val question = if (mobile) s.phoneQuestion else s.computerQuestion
    val answer = if (mobile) s.phoneAnswer else s.computerAnswer
    val status = if (mobile) s.phoneStatus else s.computerStatus
    val history = if (mobile) model.phoneHistory else model.computerHistory
    val configured =
        if (mobile)
            listOf("modelUrl", "modelName", "modelKey").all {
                model.config.optString(it).isNotBlank()
            }
        else listOf("codexUrl", "codexToken").all { model.config.optString(it).isNotBlank() }
    val draft = if (mobile) model.phoneDraft else model.computerDraft
    val inputReady = s.available && configured && (mobile || s.computerConnected) && !running
    val phoneList = rememberLazyListState()
    val computerList = rememberLazyListState()
    val list = if (mobile) phoneList else computerList
    var followLatest by remember(source) { mutableStateOf(true) }
    LaunchedEffect(list, source) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }
            .distinctUntilChanged()
            .collect { (scrolling, forward) -> if (scrolling) followLatest = !forward }
    }
    LaunchedEffect(source, answer, question, s.approvals.size) {
        withFrameNanos {}
        if (followLatest && list.layoutInfo.totalItemsCount > 0)
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SegmentedButton(
                mobile,
                { mobile = true },
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 8.dp,
                    bottomStart = 8.dp,
                ),
            ) {
                Text(if (s.phoneRunning) "Phone model · Working" else "Phone model")
            }
            SegmentedButton(
                !mobile,
                { mobile = false },
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topEnd = 8.dp,
                    bottomEnd = 8.dp,
                ),
            ) {
                Text(
                    if (s.approvals.isNotEmpty()) "Computer · Reply needed"
                    else if (s.computerRunning) "Computer · Working" else "Computer Codex"
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (mobile && configured && question.isBlank() && !running) "Model configured · Ready for questions"
                else status,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            IconButton(
                onClick = { model.newConversation(source) },
                enabled = s.available && !running && (mobile || configured),
            ) {
                Icon(Icons.Outlined.AddComment, "New conversation")
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MicNone, null, Modifier.size(20.dp))
                    Text("Glasses voice", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                    TextButton(
                        onClick = { model.attempt {
                            if (s.glassesVoice.enabled) model.service().stopGlassesVoice()
                            else model.service().startGlassesVoice(source)
                        } },
                        enabled = s.glassesVoice.enabled || (inputReady && s.glasses.link.phase == LinkPhase.FULL),
                    ) { Text(if (s.glassesVoice.enabled) "Stop voice" else "Start glasses voice") }
                }
                Text(
                    if (s.glassesVoice.enabled) "${s.glassesVoice.target} · ${s.glassesVoice.detail}" else s.glassesVoice.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.glassesVoice.phase == GlassesVoice.Phase.SPEAKING) {
                    TextButton(onClick = { model.attempt { model.service().sendGlassesSpeech() } }) { Text("Send speech now") }
                }
                if (!s.glassesVoice.enabled) Text(
                    "While enabled, glasses speech is sent to OpenAI in English using your API key. The app does not save audio. Speak, then pause to ask.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!configured)
                item {
                    ContentCard {
                        Icon(
                            if (mobile) Icons.Outlined.AutoAwesome else Icons.Outlined.Computer,
                            null,
                            Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (mobile) "Phone model not configured" else "Computer not configured",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Note(
                            if (mobile) "Use your model service on the phone. Check navigation status or send a notification to the glasses."
                            else "Connect to Codex on your computer to follow tasks, review approvals and receive answers."
                        )
                        ToolButton(onClick = { configure(if (mobile) "model" else "computer") }) {
                            Text(if (mobile) "Set up phone model" else "Set up computer connection")
                        }
                    }
                }
            else if (!mobile && !s.computerConnected)
                item {
                    ContentCard {
                        Text("Connect to computer Codex", style = MaterialTheme.typography.titleLarge)
                        Note("Start the computer bridge first. USB mode requires a data cable and port forwarding.")
                        ToolButton(
                            onClick = { model.attempt { model.service().connectCodex() } },
                            enabled = s.available,
                        ) {
                            Text("Connect / Resume")
                        }
                        TextButton(onClick = { configure("computer") }) { Text("Check connection settings") }
                    }
                }
            else if (question.isBlank() && history.isEmpty())
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            null,
                            Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("New conversation", style = MaterialTheme.typography.headlineSmall)
                        Note(if (mobile) "Try asking: What is the glasses navigation status?" else "Describe a task for Codex on your computer.")
                    }
                }
            history.forEach { record -> item { ConversationTurn(record.question, record.answer) } }
            if (question.isNotBlank())
                item {
                    ConversationTurn(question, answer)
                    if (running) {
                        Row(
                            Modifier.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Note(if (!mobile && s.approvals.isNotEmpty()) "Waiting for your reply" else "Working…")
                        }
                    } else if (answer.isNotBlank()) {
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ToolOutlinedButton(
                                onClick = {
                                    model.attempt { model.service().revealAnswer(source) }
                                },
                                enabled = s.glasses.link.phase == LinkPhase.FULL,
                            ) {
                                Text("Show on glasses")
                            }
                            val context = LocalContext.current
                            TextButton(
                                onClick = {
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Answer", answer))
                                    model.notice = "Answer copied"
                                }
                            ) {
                                Text("Copy")
                            }
                        }
                    }
                }
            if (!mobile)
                s.approvals.forEach { request ->
                    item(key = "approval:${request.id}") { ApprovalCard(model, request) }
                }
            if ((s.glasses.intent as? DisplayIntent.Answer)?.ref?.source == source)
                item {
                    ContentCard {
                        Text("Glasses output", style = MaterialTheme.typography.titleSmall)
                        GlassesOutputPreview(s.glasses)
                        if (
                            (s.glasses.intent as? DisplayIntent.Answer)?.returnTo ==
                                ReturnTarget.NAVIGATION
                        )
                            Note("Navigation resumes about 12 seconds after sending the answer. You can also close it now.")
                        TextButton(
                            onClick = { model.attempt { model.service().dismissAnswer() } }
                        ) {
                            Text("Close answer on glasses")
                        }
                    }
                }
            item(key = "conversation-end") { Spacer(Modifier.height(1.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        draft,
                        { if (mobile) model.phoneDraft = it else model.computerDraft = it },
                        Modifier.weight(1f),
                        placeholder = { Text(if (mobile) "Ask your phone model…" else "A task for your computer…") },
                        maxLines = 5,
                        shape = MaterialTheme.shapes.large,
                    )
                    if (running)
                        FilledIconButton(
                            onClick = { model.attempt { model.service().cancelAnswer(source) } },
                            Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Stop, "Stop current task")
                        }
                    else
                        FilledIconButton(
                            onClick = {
                                followLatest = true
                                model.send(source)
                            },
                            enabled = inputReady && draft.isNotBlank(),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                "Send to ${if (mobile) "Phone model" else "Computer Codex"}",
                            )
                        }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { actions.voice(source) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(Icons.Outlined.MicNone, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Phone voice input", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        if (mobile) "Send to Phone model" else "Send to Computer Codex",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationTurn(question: String, answer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer {
                Text(
                    question,
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (answer.isNotBlank())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "OpenRayneo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        answer.split("```").forEachIndexed { index, part ->
                            if (index % 2 == 0)
                                Text(part.trim(), style = MaterialTheme.typography.bodyLarge)
                            else
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        part.trim(),
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                        }
                    }
                }
            }
    }
}

@Composable
private fun ApprovalCard(model: AppModel, request: CodexClient.Request) {
    val answers = remember(request.id) { mutableStateMapOf<String, String>() }
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, null)
            Text("Your computer needs a reply", style = MaterialTheme.typography.titleLarge)
            if (request.method == "item/tool/requestUserInput") {
                val questions = request.params.getJSONArray("questions")
                for (index in 0 until questions.length()) {
                    val question = questions.getJSONObject(index)
                    val id = question.getString("id")
                    Text(question.getString("question"), fontWeight = FontWeight.Medium)
                    question.optJSONArray("options")?.let { options ->
                        for (i in 0 until options.length()) {
                            val option = options.getJSONObject(i)
                            FilterChip(
                                selected = answers[id] == option.optString("label"),
                                onClick = { answers[id] = option.optString("label") },
                                label = { Text(option.optString("label")) },
                            )
                            if (option.optString("description").isNotBlank())
                                Note(option.optString("description"))
                        }
                    }
                    OutlinedTextField(
                        answers[id].orEmpty(),
                        { answers[id] = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Your reply") },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    if (question.optBoolean("isSecret"))
                                        androidx.compose.ui.text.input.KeyboardType.Password
                                    else androidx.compose.ui.text.input.KeyboardType.Text
                            ),
                        visualTransformation =
                            if (question.optBoolean("isSecret"))
                                androidx.compose.ui.text.input.PasswordVisualTransformation()
                            else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                ToolButton(
                    onClick = {
                        model.attempt {
                            val response = JSONObject()
                            answers.forEach { (id, value) ->
                                response.put(
                                    id,
                                    JSONObject().put("answers", JSONArray().put(value)),
                                )
                            }
                            model
                                .service()
                                .codex
                                .reply(request, JSONObject().put("answers", response))
                        }
                    },
                    enabled =
                        (0 until questions.length()).all {
                            answers[questions.getJSONObject(it).getString("id")]?.isNotBlank() ==
                                true
                        },
                ) {
                    Text("Submit reply")
                }
            } else {
                SelectionContainer {
                    Text(request.details, style = MaterialTheme.typography.bodyMedium)
                }
                ToolButton(
                    onClick = {
                        model.attempt {
                            model
                                .service()
                                .codex
                                .reply(request, JSONObject().put("decision", "accept"))
                        }
                    }
                ) {
                    Text("Allow once")
                }
                ToolOutlinedButton(
                    onClick = {
                        model.attempt {
                            model
                                .service()
                                .codex
                                .reply(request, JSONObject().put("decision", "decline"))
                        }
                    }
                ) {
                    Text("Deny")
                }
            }
        }
    }
}
