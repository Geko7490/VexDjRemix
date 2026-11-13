package com.vexstudio.mashup.studio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.vexstudio.mashup.audio.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.*

@Serializable
data class Imported(val id: String, val title: String, val analysis: Analysis)

enum class Stage { DOWNLOAD, DECODE, ANALYZE, VERIFY, LOAD_MODEL, STEM_A, STEM_B, ARRANGE, WARP, MIX, EXPORT, CLEAN }

data class Progress(val stage: Stage, val done: Long = 0, val total: Long = 0)

class StudioFiles(private val context: Context) {
    private val root = File(context.filesDir, "studio").apply { mkdirs() }
    private val sources = File(root, "sources").apply { mkdirs() }
    private val projects = File(root, "projects").apply { mkdirs() }
    val models = ModelStore(File(root, "models"))
    private val cache = File(context.cacheDir, "renders").apply { mkdirs() }
    private val shares = File(context.cacheDir, "exports").apply { mkdirs() }
    private val codec = Json { ignoreUnknownKeys = true }
    private val analyze = MusicAnalysis()

    fun import(uri: Uri, cancel: Cancellation, report: (Progress) -> Unit): Imported {
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Audio"
        val dir = File(sources, "import-${UUID.randomUUID()}").apply { mkdirs() }
        val pcm = File(dir, "input.raw")
        try {
            check(sources.usableSpace > 128_000_000L) { "STORAGE" }
            report(Progress(Stage.DECODE))
            MediaInput(context).decode(uri, pcm, cancel::check) { done, total ->
                report(Progress(Stage.DECODE, done.coerceAtLeast(0), total))
            }
            val hash = hashFile(pcm, cancel)
            val target = sourceDir(hash)
            val meta = File(target, "analysis.json")
            if (meta.isFile) return Imported(hash, title, codec.decodeFromString<Analysis>(meta.readText()))
            report(Progress(Stage.ANALYZE))
            val analysis = analyze.analyze(pcm, cancel::check)
            require(analysis.duration >= 3) { "TOO_SHORT" }
            require(analysis.rmsDb > -64) { "SILENT" }
            atomic(File(dir, "analysis.json"), codec.encodeToString(Analysis.serializer(), analysis))
            cancel.check()
            check(dir.renameTo(target)) { "STORAGE" }
            return Imported(hash, title, analysis)
        } finally {
            dir.deleteRecursively()
        }
    }

    fun create(a: Imported, b: Imported, mode: Mode, threads: Int, cancel: Cancellation, report: (Progress) -> Unit): Project {
        require(a.id != b.id) { "SAME_SONG" }
        require(a.analysis.bpm > 0 && b.analysis.bpm > 0) { "NO_BEAT" }
        require(min(a.analysis.stability, b.analysis.stability) >= .17) { "UNSTABLE" }
        val missing = listOf(a, b).filter { !stemsReady(it) }
        if (missing.isNotEmpty()) {
            val bytes = missing.sumOf { input(it).length() * 4 }
            check(sources.usableSpace > bytes + 64_000_000) { "STORAGE" }
            report(Progress(Stage.VERIFY))
            val model = models.verified(cancel)
            report(Progress(Stage.LOAD_MODEL))
            StemSeparator(model, threads).use { separator ->
                for (song in missing) {
                    cancel.check()
                    val stage = if (song.id == a.id) Stage.STEM_A else Stage.STEM_B
                    report(Progress(stage))
                    val target = stemsDir(song.id)
                    val partial = File(target.parentFile, "stems.partial")
                    partial.deleteRecursively()
                    partial.mkdirs()
                    try {
                        separator.separate(input(song), partial, cancel) { done, total -> report(Progress(stage, done, total)) }
                        val stats = Stem.entries.associateWith {
                            stemStats(File(partial, "${it.name}.raw"), song.analysis, cancel)
                        }
                        atomic(File(partial, "complete.json"), codec.encodeToString(
                            kotlinx.serialization.builtins.MapSerializer(Stem.serializer(), StemStats.serializer()), stats))
                        target.deleteRecursively()
                        check(partial.renameTo(target)) { "STORAGE" }
                    } finally {
                        partial.deleteRecursively()
                    }
                }
            }
        }
        report(Progress(Stage.ARRANGE))
        val sourceA = source(a)
        val sourceB = source(b)
        val choices = MashupPlanner().plan(sourceA, sourceB, mode)
        val project = Project(UUID.randomUUID().toString(), "${a.title.substringBeforeLast('.')} × ${b.title.substringBeforeLast('.')}",
            System.currentTimeMillis(), mode, sourceA, sourceB, choices)
        cancel.check()
        save(project)
        return project
    }

    fun save(project: Project) = atomic(File(projects, "${project.id}.json"), codec.encodeToString(Project.serializer(), project))

    fun list(): List<Project> = projects.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { codec.decodeFromString<Project>(it.readText()) }.getOrNull() }
        .sortedByDescending { it.created }

    fun delete(id: String) { File(projects, "$id.json").delete() }

    fun clean(keep: Set<String>) {
        val active = list().flatMap { listOf(it.a.id, it.b.id) }.toSet() + keep
        sources.listFiles().orEmpty().filterNot { it.name in active }.forEach { it.deleteRecursively() }
        sources.listFiles().orEmpty().filter { it.name in active }.forEach { File(it, "stems.partial").deleteRecursively() }
        cache.listFiles().orEmpty().forEach { it.deleteRecursively() }
        shares.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    fun render(project: Project, cancel: Cancellation, report: (Progress) -> Unit): Pair<File, RenderStats> {
        cache.mkdirs()
        val key = MessageDigest.getInstance("SHA-256").digest(codec.encodeToString(Arrangement.serializer(), project.arrangement)
            .plus(project.mode.name).toByteArray()).hex()
        val mixed = File(cache, "$key.raw")
        val metadata = File(cache, "$key.stats")
        if (mixed.isFile && metadata.isFile) {
            val data = metadata.readText().split(',')
            return mixed to RenderStats(data[0].toDouble(), data[1].toDouble(), data[2].toLong())
        }
        check(cache.usableSpace > 96_000_000L) { "STORAGE" }
        val warpDir = File(cache, "clips").apply { mkdirs() }
        val mixer = Mixer(
            { track -> File(stemsDir(track.source), "${track.stem.name}.raw").also { check(it.isFile) { "MISSING_STEMS" } } },
            { track ->
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(listOf(track.source, track.stem, track.start, track.length, track.speed, track.pitch).joinToString("|").toByteArray()).hex()
                File(warpDir, "$hash.raw")
            }
        )
        val partial = File(cache, "$key.partial")
        try {
            report(Progress(Stage.WARP))
            val stats = mixer.render(project.arrangement, project.mode, partial, cancel::check) { done, total ->
                report(Progress(Stage.MIX, done, total))
            }
            cancel.check()
            check(partial.renameTo(mixed)) { "STORAGE" }
            atomic(metadata, "${stats.rmsDb},${stats.peakDb},${stats.overloads}")
            pruneRenderCache(warpDir, mixed, metadata)
            return mixed to stats
        } finally {
            partial.delete()
        }
    }

    fun wav(project: Project, quality: WavQuality, cancel: Cancellation, report: (Progress) -> Unit): Pair<File, RenderStats> {
        val (mixed, stats) = render(project, cancel, report)
        val file = File(shares.apply { mkdirs() }, "${project.id}-${System.nanoTime()}.wav")
        try {
            report(Progress(Stage.EXPORT))
            file.outputStream().buffered().use { output ->
                WavOutput.write(mixed, output, quality, cancel::check) { done, total -> report(Progress(Stage.EXPORT, done, total)) }
            }
            val old = shares.listFiles().orEmpty().filterNot { it == file }.sortedByDescending { it.lastModified() }
            old.drop(5).forEach { it.delete() }
            return file to stats
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun pruneRenderCache(warps: File, mix: File, stats: File) {
        val files = (warps.listFiles().orEmpty().toList() + cache.listFiles().orEmpty())
            .filter { it.isFile && it != mix && it != stats }.sortedByDescending { it.lastModified() }
        var total = 0L
        files.forEach {
            total += it.length()
            if (total > 240_000_000) it.delete()
        }
    }

    private fun input(song: Imported) = File(sourceDir(song.id), "input.raw")
    private fun sourceDir(id: String) = File(sources, id)
    private fun stemsDir(id: String) = File(sourceDir(id), "stems-${ModelStore.SHA.take(12)}")
    private fun stemsReady(song: Imported): Boolean {
        val dir = stemsDir(song.id)
        return File(dir, "complete.json").isFile && Stem.entries.all { File(dir, "${it.name}.raw").length() == input(song).length() }
    }
    private fun source(song: Imported): Source {
        val json = File(stemsDir(song.id), "complete.json").readText()
        val stats = codec.decodeFromString(kotlinx.serialization.builtins.MapSerializer(Stem.serializer(), StemStats.serializer()), json)
        return Source(song.id, song.title, song.analysis, stats)
    }

    private fun stemStats(file: File, analysis: Analysis, cancel: Cancellation): StemStats = PcmReader(file).use { reader ->
        val energy = FloatArray(analysis.energy.size)
        val counts = IntArray(energy.size)
        val waveform = FloatArray(180)
        var power = 0.0
        for (start in 0 until reader.frames step BLOCK * 8) {
            cancel.check()
            val count = min(BLOCK * 8, reader.frames - start)
            val data = reader.read(start, count)
            for (i in 0 until count) {
                val l = data[i * 2]
                val r = data[i * 2 + 1]
                val p = (l * l + r * r) * .5f
                power += p
                val bucket = ((start + i) / (RATE / 4)).coerceIn(energy.indices)
                energy[bucket] += p
                counts[bucket]++
                val bin = ((start + i) * waveform.size.toLong() / reader.frames).toInt()
                waveform[bin] = max(waveform[bin], max(abs(l), abs(r)))
            }
        }
        for (i in energy.indices) energy[i] = sqrt(energy[i] / max(1, counts[i]))
        StemStats(10 * log10(max(1e-12, power / max(1, reader.frames))), energy.toList(), waveform.toList())
    }

    private fun hashFile(file: File, cancel: Cancellation): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                cancel.check()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().hex()
    }

    private fun atomic(file: File, value: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray())
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }
}
