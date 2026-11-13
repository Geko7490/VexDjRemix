package com.vexstudio.mashup.studio

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vexstudio.mashup.audio.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorState(
    val a: Imported? = null, val b: Imported? = null, val mode: Mode = Mode.AUTO,
    val threads: Int = 3, val installed: Boolean = false, val current: Project? = null,
    val projects: List<Project> = emptyList(), val undo: Boolean = false, val redo: Boolean = false,
    val playing: Boolean = false, val hasAudio: Boolean = false, val playTime: Double = 0.0,
    val stats: RenderStats? = null, val message: String? = null, val share: File? = null
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val files = StudioFiles(application)
    private val preferences = application.getSharedPreferences("studio", 0)
    private val mutable = MutableStateFlow(EditorState(threads = preferences.getInt("threads", 3), installed = files.models.installed))
    val state = mutable.asStateFlow()
    val job = StudioJobs.state
    private val past = ArrayDeque<Project>()
    private val future = ArrayDeque<Project>()
    private val writes = kotlinx.coroutines.channels.Channel<Project>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var player: MediaPlayer? = null
    private var preparing = false
    private var pendingSeek = 0.0

    init {
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            for (project in writes) {
                runCatching { files.save(project) }.onFailure { mutable.update { it.copy(message = "STORAGE") } }
                val projects = files.list()
                mutable.update { it.copy(projects = projects) }
            }
        }
        viewModelScope.launch {
            job.map { it.result }.filterNotNull().collect { result ->
                when (result) {
                    is Result.Song -> mutable.update {
                        if (result.slot == 0) it.copy(a = result.imported) else it.copy(b = result.imported)
                    }
                    is Result.Created -> { open(result.project); refresh() }
                    is Result.Audio -> if (result.share) mutable.update { it.copy(share = result.file, stats = result.stats) }
                        else play(result.file, result.stats)
                    is Result.Failed -> mutable.update { it.copy(message = result.code) }
                    Result.Done -> { refresh(); mutable.update { it.copy(message = "DONE") } }
                    Result.Cancelled -> mutable.update { it.copy(message = "CANCELLED") }
                }
                StudioJobs.acknowledge(result)
            }
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(160)
                val active = player
                if (active != null && !preparing && mutable.value.playing) {
                    mutable.update { it.copy(playTime = active.currentPosition / 1000.0) }
                }
            }
        }
    }

    fun start(task: Task) {
        mutable.update { it.copy(message = null) }
        StudioJobs.start(getApplication(), task)
    }

    fun create() {
        val (a, b, mode, threads) = mutable.value
        if (a != null && b != null) start(Task.Create(a, b, mode, threads))
    }

    fun mode(mode: Mode) { mutable.update { it.copy(mode = mode) } }
    fun threads(count: Int) {
        preferences.edit().putInt("threads", count).apply()
        mutable.update { it.copy(threads = count) }
    }

    fun open(project: Project) {
        closePlayer()
        past.clear()
        future.clear()
        mutable.update { it.copy(current = project, undo = false, redo = false, playTime = 0.0, message = null) }
    }

    fun closeProject() {
        closePlayer()
        past.clear()
        future.clear()
        mutable.update { it.copy(current = null, undo = false, redo = false, playTime = 0.0) }
        refresh()
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { files.delete(id) }
            refresh()
        }
    }

    fun change(update: (Project) -> Project) {
        val current = mutable.value.current ?: return
        val next = update(current)
        if (current == next) return
        past.addLast(current)
        if (past.size > 60) past.removeFirst()
        future.clear()
        apply(next)
    }

    fun track(index: Int, update: (Track) -> Track) = change { project ->
        val choices = project.arrangements.toMutableList()
        val tracks = project.arrangement.tracks.toMutableList()
        tracks[index] = update(tracks[index])
        choices[project.selected] = project.arrangement.copy(tracks = tracks)
        project.copy(arrangements = choices)
    }

    fun undo() {
        val project = past.removeLastOrNull() ?: return
        mutable.value.current?.let(future::addLast)
        apply(project)
    }

    fun redo() {
        val project = future.removeLastOrNull() ?: return
        mutable.value.current?.let(past::addLast)
        apply(project)
    }

    private fun apply(project: Project) {
        val old = mutable.value.current
        if (old?.arrangement != project.arrangement) closePlayer()
        mutable.update { it.copy(current = project, undo = past.isNotEmpty(), redo = future.isNotEmpty()) }
        writes.trySend(project)
    }

    fun preview(seconds: Double) {
        val active = player
        if (preparing) return
        if (active != null) {
            if (active.isPlaying) active.pause()
            else active.start()
            mutable.update { it.copy(playing = active.isPlaying) }
            return
        }
        pendingSeek = seconds
        mutable.value.current?.let { start(Task.Preview(it)) }
    }

    fun seek(seconds: Double) {
        val active = player ?: return
        if (preparing) return
        active.seekTo((seconds * 1000).toInt().coerceIn(0, active.duration))
        mutable.update { it.copy(playTime = seconds) }
    }

    fun clean() {
        closePlayer()
        val current = mutable.value
        start(Task.Clean(listOfNotNull(current.a?.id, current.b?.id).toSet()))
    }

    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun dismissShare() { mutable.update { it.copy(share = null) } }

    private fun play(file: File, stats: RenderStats) {
        closePlayer()
        val active = MediaPlayer()
        player = active
        preparing = true
        try {
            active.setDataSource(file.absolutePath)
            active.setOnPreparedListener {
                if (it !== player) return@setOnPreparedListener
                preparing = false
                it.seekTo((pendingSeek * 1000).toInt().coerceIn(0, (it.duration - 1).coerceAtLeast(0)))
                it.start()
                mutable.update { state -> state.copy(playing = true, hasAudio = true, playTime = pendingSeek, stats = stats) }
            }
            active.setOnCompletionListener {
                if (it === player) mutable.update { state -> state.copy(playing = false, playTime = 0.0) }
            }
            active.setOnErrorListener { _, _, _ ->
                closePlayer()
                mutable.update { it.copy(message = "INVALID_AUDIO") }
                true
            }
            active.prepareAsync()
        } catch (error: Exception) {
            closePlayer()
            mutable.update { it.copy(message = "INVALID_AUDIO") }
        }
    }

    private fun closePlayer() {
        player?.release()
        player = null
        preparing = false
        mutable.update { it.copy(playing = false, hasAudio = false, stats = null) }
    }

    private fun refresh() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) { files.list() }
            mutable.update { it.copy(projects = projects, installed = files.models.installed) }
        }
    }

    override fun onCleared() { closePlayer() }
}
