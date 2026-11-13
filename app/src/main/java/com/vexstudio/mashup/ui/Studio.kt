package com.vexstudio.mashup.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vexstudio.mashup.R
import com.vexstudio.mashup.audio.*
import com.vexstudio.mashup.studio.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal val purple = Color(0xFFC49BFF)
internal val cyan = Color(0xFF80DBD5)
internal val panel = Color(0xFF131017)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Studio(vm: StudioViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    var bits by rememberSaveable { mutableIntStateOf(24) }
    var rate by rememberSaveable { mutableIntStateOf(44100) }
    var license by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.start(Task.Import(slot, uri))
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val project = state.current
        if (uri != null && project != null) vm.start(Task.Export(project, WavQuality(rate, bits), uri))
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(context.getString(messageId(message)))
        vm.dismissMessage()
    }
    LaunchedEffect(state.share) {
        val file = state.share ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val intent = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        vm.dismissShare()
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, onPrimary = Color(0xFF261341),
        secondary = cyan, background = Color.Black, surface = panel, surfaceVariant = Color(0xFF21192C),
        onSurface = Color(0xFFF2EBFA), onSurfaceVariant = Color(0xFFBEB3CB))) {
        Scaffold(containerColor = Color.Black, snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("VEX", color = purple, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                        Text(state.current?.title ?: "Mashup Studio", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }, navigationIcon = {
                    if (state.current != null) TextButton(onClick = vm::closeProject, enabled = !job.busy) { Text("‹") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black))
            },
            bottomBar = {
                if (state.current == null) {
                    val labels = listOf(R.string.workspace, R.string.projects, R.string.models, R.string.settings)
                    NavigationBar(containerColor = panel) {
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                icon = { Text(listOf("≋", "▤", "◇", "⚙")[index], style = MaterialTheme.typography.titleLarge) },
                                label = { Text(stringResource(label)) })
                        }
                    }
                }
            }) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                if (job.busy) ProgressPane(job)
                val project = state.current
                if (project != null) {
                    Editor(project, state, job.busy, vm, rate, bits, { rate = it }, { bits = it },
                        { export.launch("${project.title.replace(Regex("[/\\\\:]"), "_")}.wav") },
                        { vm.start(Task.Share(project, WavQuality(rate, bits))) })
                } else when (tab) {
                    0 -> Home(state, job.busy, vm::mode, {
                        slot = it
                        choose.launch(arrayOf("audio/*", "application/octet-stream"))
                    }, vm::create, { tab = 2 })
                    1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Title(stringResource(R.string.projects)) }
                        if (state.projects.isEmpty()) item { Text(stringResource(R.string.no_projects), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(state.projects, key = { it.id }) { item ->
                            Surface(shape = RoundedCornerShape(16.dp), color = panel, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable(enabled = !job.busy) { vm.open(item) }.padding(vertical = 8.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.created)),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { deleteId = item.id }, enabled = !job.busy) { Text(stringResource(R.string.delete)) }
                                }
                            }
                        }
                    }
                    2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Title("HTDemucs")
                        Text(stringResource(if (state.installed) R.string.installed else R.string.not_installed), color = purple)
                        Text(stringResource(R.string.model_details))
                        Button(onClick = { vm.start(Task.Install) }, enabled = !job.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (state.installed) R.string.reinstall else R.string.install))
                        }
                        if (state.installed) OutlinedButton(onClick = { vm.start(Task.Remove) }, enabled = !job.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Title(stringResource(R.string.settings))
                        Text(stringResource(R.string.threads), style = MaterialTheme.typography.titleMedium)
                        listOf(2 to R.string.eco, 3 to R.string.balanced, 4 to R.string.fast).forEach { (count, label) ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !job.busy) { vm.threads(count) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.threads == count, onClick = { vm.threads(count) }, enabled = !job.busy)
                                Text(stringResource(label))
                            }
                        }
                        HorizontalDivider()
                        OutlinedButton(onClick = vm::clean, enabled = !job.busy) { Text(stringResource(R.string.clean)) }
                        TextButton(onClick = { license = true }) { Text(stringResource(R.string.licenses)) }
                        Text(stringResource(R.string.privacy), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.language))
                        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                        }) { Text(stringResource(R.string.language_settings)) }
                    }
                }
            }
        }
        if (license) {
            val content by produceState("", context) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                        context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                    }
                }
            }
            AlertDialog(onDismissRequest = { license = false }, title = { Text(stringResource(R.string.licenses)) },
                text = { Text(content, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { license = false }) { Text(stringResource(R.string.close)) } })
        }
        if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.delete_project)) },
            confirmButton = { TextButton(onClick = { deleteId?.let(vm::delete); deleteId = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ProgressPane(job: JobState) {
    val stages = stringArrayResource(R.array.stages)
    val progress = job.progress
    Surface(color = panel, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progress?.let { stages[it.stage.ordinal] } ?: stringResource(R.string.processing), Modifier.weight(1f))
                TextButton(onClick = StudioJobs::cancel) { Text(stringResource(R.string.cancel)) }
            }
            if (progress != null && progress.total > 0) {
                val fraction = (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(if (progress.stage == Stage.STEM_A || progress.stage == Stage.STEM_B) "${progress.done} / ${progress.total}"
                    else "${(100 * fraction).toInt()}%", Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall)
            } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 7.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(state: EditorState, busy: Boolean, setMode: (Mode) -> Unit, pick: (Int) -> Unit, create: () -> Unit, model: () -> Unit) {
    val modes = stringArrayResource(R.array.modes)
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Title(stringResource(R.string.new_project))
        SongCard(0, state.a, purple, busy, pick)
        SongCard(1, state.b, cyan, busy, pick)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy) expanded = it }) {
            OutlinedTextField(value = modes[state.mode.ordinal], onValueChange = {}, readOnly = true, enabled = !busy,
                label = { Text(stringResource(R.string.mashup_mode)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Mode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(modes[mode.ordinal]) }, onClick = { setMode(mode); expanded = false })
                }
            }
        }
        Button(onClick = create, enabled = state.a != null && state.b != null && !busy,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(55.dp)) {
            Text(stringResource(R.string.create), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (!state.installed) TextButton(onClick = model) { Text(stringResource(R.string.model_needed)) }
    }
}

@Composable
private fun SongCard(index: Int, song: Imported?, tint: Color, busy: Boolean, pick: (Int) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (index == 0) R.string.track_a else R.string.track_b), color = tint,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (song != null) Text(stringResource(R.string.ready), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (song != null) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                val analysis = song.analysis
                val bpm = if (analysis.bpm > 0) "${number(analysis.bpm, 1)} BPM" else stringResource(R.string.unknown)
                Text("${clock(analysis.duration)}  ·  $bpm  ·  ${analysis.key?.label() ?: stringResource(R.string.unknown)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Wave(analysis.waveform, tint, Modifier.fillMaxWidth().height(36.dp))
            }
            OutlinedButton(onClick = { pick(index) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (song == null) R.string.choose_audio else R.string.replace))
            }
        }
    }
}

@Composable
internal fun Title(label: String) {
    Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Wave(values: List<Float>, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val step = size.width / values.size
        val middle = size.height / 2
        values.forEachIndexed { i, value ->
            val height = kotlin.math.sqrt(value.coerceIn(0f, 1f)) * middle * .94f
            val x = (i + .5f) * step
            drawLine(tint, Offset(x, middle - height), Offset(x, middle + height), strokeWidth = step * .64f, cap = StrokeCap.Round)
        }
    }
}

internal fun number(value: Double, digits: Int = 2): String = String.format(Locale.getDefault(), "%.${digits}f", value)
internal fun clock(seconds: Double): String = "%d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)

private fun messageId(code: String): Int = when (code) {
    "DONE" -> R.string.done
    "CANCELLED" -> R.string.cancelled
    "NO_MATCH" -> R.string.err_match
    "NO_BEAT", "UNSTABLE" -> R.string.err_beat
    "MODEL_MISSING", "MODEL_CONTRACT", "CHECKSUM", "INFERENCE" -> R.string.err_model
    "STORAGE" -> R.string.err_storage
    "INVALID_AUDIO", "NO_AUDIO", "FORMAT_CHANGED" -> R.string.err_audio
    "TOO_LONG", "TOO_SHORT" -> R.string.err_duration
    "SILENT" -> R.string.err_silent
    "MEMORY" -> R.string.err_memory
    "DOWNLOAD" -> R.string.err_download
    "SAME_SONG" -> R.string.err_same
    "DRM" -> R.string.err_drm
    "BACKGROUND" -> R.string.err_background
    "MISSING_STEMS" -> R.string.err_stems
    else -> R.string.err_processing
}
