package com.mythara.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.imports.MessagePersonaExtractor
import com.mythara.imports.SmsImporter
import com.mythara.imports.WhatsAppExportImporter
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessageImportPanelViewModel @Inject constructor(
    private val smsImporter: SmsImporter,
    private val waImporter: WhatsAppExportImporter,
    private val extractor: MessagePersonaExtractor,
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun importSms() {
        viewModelScope.launch {
            _busy.value = true
            _status.value = "${Glyph.Ellipsis} reading SMS history…"
            val out = runCatching { smsImporter.import() }.getOrElse {
                _status.value = "× ${it.message ?: "import failed"}"
                _busy.value = false
                return@launch
            }
            if (!out.ok) {
                _status.value = "× ${out.detail ?: "couldn't read SMS"}"
                _busy.value = false
                return@launch
            }
            _status.value = "${Glyph.Ellipsis} analysing ${out.messages.size} messages (Gemma pass may take ~2 min)…"
            val report = runCatching { extractor.extractAndPersist("sms", out.messages) }
                .getOrElse {
                    _status.value = "× analysis failed: ${it.message}"
                    _busy.value = false
                    return@launch
                }
            _status.value = "${Glyph.Check} learned ${report.recordsWritten} traits from ${report.messagesAnalyzed} SMS messages"
            _busy.value = false
        }
    }

    fun importWhatsApp(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _status.value = "${Glyph.Ellipsis} parsing WhatsApp export…"
            val out = runCatching { waImporter.import(uri) }.getOrElse {
                _status.value = "× ${it.message ?: "parse failed"}"
                _busy.value = false
                return@launch
            }
            if (!out.ok) {
                _status.value = "× ${out.detail ?: "couldn't parse export"}"
                _busy.value = false
                return@launch
            }
            _status.value = "${Glyph.Ellipsis} analysing ${out.messages.size} messages (Gemma pass may take ~2 min)…"
            val report = runCatching { extractor.extractAndPersist("whatsapp", out.messages) }
                .getOrElse {
                    _status.value = "× analysis failed: ${it.message}"
                    _busy.value = false
                    return@launch
                }
            _status.value = "${Glyph.Check} learned ${report.recordsWritten} traits from ${report.messagesAnalyzed} WhatsApp messages"
            _busy.value = false
        }
    }
}

/**
 * One-time import of message history (SMS via ContentProvider,
 * WhatsApp via the user-exported `.txt` chat dump). Each import
 * produces a handful of persona-trait vault records — top
 * contacts, peak hour, communication style — and stops there.
 * Raw messages are never persisted.
 */
@Composable
fun MessageImportPanel(vm: MessageImportPanelViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current

    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.importSms()
    }
    val waFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) vm.importWhatsApp(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} import message history",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.READ_SMS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) vm.importSms()
                    else smsPermLauncher.launch(Manifest.permission.READ_SMS)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("${Glyph.Arrow} import SMS")
            }

            Button(
                enabled = !busy,
                onClick = {
                    // ACTION_OPEN_DOCUMENT with text/plain — WhatsApp's
                    // "Export chat" output. Other text dumps work too
                    // if the user wants to retry an import.
                    waFilePicker.launch(arrayOf("text/plain", "application/zip", "*/*"))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Surface,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("${Glyph.Arrow} import WhatsApp .txt")
            }
        }

        status?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = msg,
                color = if (msg.startsWith(Glyph.Check)) MytharaColors.Julep
                else if (msg.startsWith("×")) MytharaColors.Sriracha
                else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} Mythara reads your messaging history ONE TIME to learn patterns about you (top contacts, when you message most, your communication style, and — if Gemma is loaded — deeper traits like recurring topics and tone). RAW MESSAGES NEVER LEAVE THE PHONE: cheap heuristics run locally, and the deep pass uses the on-device Gemma LLM. Only the extracted traits land in Lumi's memory and sync to your GitHub backup like every other vault record. For WhatsApp: open the chat in WhatsApp → kebab menu → More → Export chat → 'Without media' → share/save to a place you can find here (Files app, Downloads).",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
