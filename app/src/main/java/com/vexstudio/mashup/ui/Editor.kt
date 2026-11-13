package com.vexstudio.mashup.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vexstudio.mashup.R
import com.vexstudio.mashup.audio.*
import com.vexstudio.mashup.studio.EditorState
import com.vexstudio.mashup.studio.StudioViewModel
import kotlin.math.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Editor(project: Project, state: EditorState, busy: Boolean, vm: StudioViewModel,
    rate: Int, bits: Int, setRate: (Int) -> Unit, setBits: (Int) -> Unit, export: () -> Unit, share: () -> Unit) {
    val mix = project.arrangement
    val names = stringArrayResource(R.array.variants)
    val sections = stringArrayResource(R.array.sections)
    val modes = stringArrayResource(R.array.modes)
    val duration = mix.tracks.maxOf { it.offset + it.length / it.speed }.coerceAtLeast(1.0)
    var expanded by rememberSaveable(project.id) { mutableStateOf(false) }
    var trackIndex by rememberSaveable(project.id, project.selected) { mutableIntStateOf(-1) }
    var snap by rememberSaveable(project.id) { mutableStateOf(true) }
    var zoom by rememberSaveable(project.id) { mutableIntStateOf(1) }
    var cursor by rememberSaveable(project.id, project.selected) { mutableDoubleStateOf(0.0) }
    val scroll = rememberScrollState()
    BackHandler(!busy, vm::closeProject)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${modes[project.mode.ordinal]}  ·  ${number(mix.bpm, 1)} BPM  ·  ${clock(duration)}",
            color = purple, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.compatibility, mix.score), Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }, enabled = !busy) { Text(stringResource(R.string.alternatives)) }
        }
        if (expanded) {
            mixOptions(project, names.toList(), sections.toList(), busy) { index ->
                vm.change { it.copy(selected = index) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Title(stringResource(R.string.timeline)) }
            TextButton(vm::undo, enabled = state.undo && !busy) { Text(stringResource(R.string.undo)) }
            TextButton(vm::redo, enabled = state.redo && !busy) { Text(stringResource(R.string.redo)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = snap, onClick = { snap = !snap }, label = { Text(stringResource(R.string.snap)) })
            AssistChip(onClick = { zoom-- }, enabled = zoom > 1, label = { Text(stringResource(R.string.zoom_out)) })
            AssistChip(onClick = { zoom++ }, enabled = zoom < 6, label = { Text(stringResource(R.string.zoom_in)) })
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth * zoom
            val density = LocalDensity.current
            val minimum = with(density) { 2.dp.toPx() }
            Column(Modifier.horizontalScroll(scroll).width(width)) {
                val displayCursor = if (state.playing) state.playTime else cursor
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val beat = 60 / mix.bpm
                    for (index in 0..ceil(duration / beat).toInt()) {
                        val x = (index * beat / duration * size.width).toFloat()
                        drawLine(if (index % 4 == 0) purple else Color.DarkGray, Offset(x, if (index % 4 == 0) 0f else size.height / 2),
                            Offset(x, size.height), if (index % 4 == 0) minimum else 1f)
                    }
                }
                mix.tracks.forEachIndexed { index, track ->
                    val source = if (track.source == project.a.id) project.a else project.b
                    val tint = if (source.id == project.a.id) purple else cyan
                    val waveform = source.stems.getValue(track.stem).waveform
                    val start = (track.start / source.analysis.duration * waveform.size).toInt().coerceIn(0, waveform.lastIndex)
                    val end = ((track.start + track.length) / source.analysis.duration * waveform.size).toInt().coerceIn(start + 1, waveform.size)
                    val region = waveform.subList(start, end)
                    val laneTint = if (track.mute) tint.copy(alpha = .3f) else tint
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(if (trackIndex == index) Color(0xFF261B37) else panel, RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { trackIndex = if (trackIndex == index) -1 else index }) {
                        Text("${stringResource(stemId(track.stem))}  ·  ${if (source.id == project.a.id) "A" else "B"}",
                            color = laneTint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 10.dp, top = 5.dp))
                        Box(Modifier.fillMaxWidth().height(34.dp)) {
                            Wave(region, laneTint, Modifier.offset(x = width * (track.offset / duration).toFloat())
                                .width(width * (track.length / track.speed / duration).toFloat()).fillMaxHeight())
                            Canvas(Modifier.matchParentSize()) {
                                project.markers.filter { it <= duration }.forEach { mark ->
                                    val x = (mark / duration * size.width).toFloat()
                                    drawLine(cyan.copy(alpha = .45f), Offset(x, 0f), Offset(x, size.height), minimum)
                                }
                                val cursorX = (displayCursor.coerceIn(0.0, duration) / duration * size.width).toFloat()
                                drawLine(Color.White.copy(alpha = .72f), Offset(cursorX, 0f), Offset(cursorX, size.height), minimum)
                            }
                        }
                    }
                }
            }
        }
        NumberSlider(R.string.position, cursor.coerceIn(0.0, duration), 0.0..duration, !busy,
            format = { clock(it) }) {
            cursor = snap(it, mix.bpm, snap).coerceIn(0.0, duration)
            vm.seek(cursor)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.change { it.copy(markers = (it.markers + cursor).distinct().sorted()) } }, enabled = !busy) {
                Text(stringResource(R.string.marker))
            }
            if (project.markers.isNotEmpty()) TextButton(onClick = { vm.change { it.copy(markers = emptyList()) } }, enabled = !busy) {
                Text(stringResource(R.string.clear_markers))
            }
        }
        if (trackIndex in mix.tracks.indices) {
            val track = mix.tracks[trackIndex]
            val source = if (track.source == project.a.id) project.a else project.b
            TrackControls(track, source.analysis.duration, mix.bpm, snap, !busy) { update -> vm.track(trackIndex, update) }
        }
        Button(onClick = { vm.preview(cursor) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(stringResource(if (state.playing) R.string.pause else if (state.hasAudio) R.string.play else R.string.preview))
        }
        state.stats?.let {
            Text(stringResource(R.string.mix_stats, it.rmsDb, it.peakDb, it.overloads),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider()
        Text(stringResource(R.string.rate), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(44100, 48000).forEach {
                FilterChip(selected = rate == it, onClick = { setRate(it) }, enabled = !busy, label = { Text("$it Hz") })
            }
        }
        Text(stringResource(R.string.depth), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(16, 24).forEach {
                FilterChip(selected = bits == it, onClick = { setBits(it) }, enabled = !busy, label = { Text("$it-bit") })
            }
        }
        Text("${number((44 + duration * rate * bits / 4) / 1_000_000, 1)} MB", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(onClick = export, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export)) }
            OutlinedButton(onClick = share, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun mixOptions(project: Project, names: List<String>, sections: List<String>, busy: Boolean, choose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        project.arrangements.forEachIndexed { index, mix ->
            val vocal = mix.tracks.first { it.stem == Stem.VOCALS }
            val backing = mix.tracks.first { it.stem == Stem.OTHER }
            Surface(shape = RoundedCornerShape(12.dp), color = if (project.selected == index) Color(0xFF281C38) else panel,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { choose(index) }) {
                Column(Modifier.padding(12.dp)) {
                    Text("${names[mix.variant.ordinal]}  ·  ${mix.score}/100", color = purple)
                    Text(stringResource(R.string.clip_range, "${if (vocal.source == project.a.id) "A" else "B"} · ${sections[mix.vocalSection.ordinal]}",
                        vocal.start, vocal.start + vocal.length), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.clip_range, "${if (backing.source == project.a.id) "A" else "B"} · ${sections[mix.backingSection.ordinal]}",
                        backing.start, backing.start + backing.length), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackControls(track: Track, duration: Double, bpm: Double, grid: Boolean, enabled: Boolean, edit: ((Track) -> Track) -> Unit) {
    Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(stemId(track.stem)), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(track.mute, { edit { it.copy(mute = !it.mute) } }, { Text(stringResource(R.string.mute)) }, enabled = enabled)
                FilterChip(track.solo, { edit { it.copy(solo = !it.solo) } }, { Text(stringResource(R.string.solo)) }, enabled = enabled)
            }
            NumberSlider(R.string.gain, track.gain.toDouble(), 0.0..3.0, enabled) { value -> edit { it.copy(gain = value.toFloat()) } }
            NumberSlider(R.string.pan, track.pan.toDouble(), -1.0..1.0, enabled) { value -> edit { it.copy(pan = value.toFloat()) } }
            NumberSlider(R.string.start, track.start, 0.0..max(.25, duration - track.length), enabled) { value ->
                edit { it.copy(start = snap(value, bpm, grid).coerceIn(0.0, max(0.0, duration - it.length))) }
            }
            NumberSlider(R.string.length, track.length, .25..max(.25, min(60.0, duration - track.start)), enabled) { value ->
                edit { it.copy(length = snap(value, bpm, grid).coerceIn(.25, max(.25, min(60.0, duration - it.start)))) }
            }
            NumberSlider(R.string.offset, track.offset, 0.0..32.0, enabled) { value -> edit { it.copy(offset = snap(value, bpm, grid)) } }
            NumberSlider(R.string.tempo, track.speed, .72..1.40, enabled) { value -> edit { it.copy(speed = value) } }
            NumberSlider(R.string.pitch, track.pitch, -6.0..6.0, enabled, format = { "${it.roundToInt()} st" }) { value ->
                edit { it.copy(pitch = value.roundToInt().toDouble()) }
            }
            NumberSlider(R.string.low, track.low.toDouble(), -12.0..12.0, enabled) { value -> edit { it.copy(low = value.toFloat()) } }
            NumberSlider(R.string.mid, track.mid.toDouble(), -12.0..12.0, enabled) { value -> edit { it.copy(mid = value.toFloat()) } }
            NumberSlider(R.string.high, track.high.toDouble(), -12.0..12.0, enabled) { value -> edit { it.copy(high = value.toFloat()) } }
            NumberSlider(R.string.wet, track.wet.toDouble(), 0.0..0.6, enabled) { value -> edit { it.copy(wet = value.toFloat()) } }
            NumberSlider(R.string.hp, track.highPass.toDouble(), 0.0..600.0, enabled, format = { "${it.roundToInt()} Hz" }) { value ->
                edit { it.copy(highPass = if (value < 15) 0f else value.toFloat()) }
            }
            NumberSlider(R.string.lp, if (track.lowPass == 0f) 20000.0 else track.lowPass.toDouble(), 1000.0..20000.0, enabled,
                format = { "${it.roundToInt()} Hz" }) { value -> edit { it.copy(lowPass = if (value >= 19990) 0f else value.toFloat()) } }
        }
    }
}

@Composable
private fun NumberSlider(label: Int, value: Double, range: ClosedFloatingPointRange<Double>, enabled: Boolean,
    format: (Double) -> String = { number(it) }, commit: (Double) -> Unit) {
    var draft by remember(value, range.start, range.endInclusive) { mutableFloatStateOf(value.toFloat()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(format(draft.toDouble()), style = MaterialTheme.typography.labelMedium, color = purple)
    }
    val limits = range.start.toFloat()..max(range.start.toFloat() + .001f, range.endInclusive.toFloat())
    Slider(value = draft.coerceIn(limits), valueRange = limits, enabled = enabled,
        onValueChange = { draft = it }, onValueChangeFinished = { commit(draft.toDouble()) })
}

private fun snap(value: Double, bpm: Double, enabled: Boolean): Double =
    if (enabled) round(value * bpm / 60) * 60 / bpm else value

private fun stemId(stem: Stem): Int = when (stem) {
    Stem.DRUMS -> R.string.drums
    Stem.BASS -> R.string.bass
    Stem.OTHER -> R.string.other
    Stem.VOCALS -> R.string.vocals
}
