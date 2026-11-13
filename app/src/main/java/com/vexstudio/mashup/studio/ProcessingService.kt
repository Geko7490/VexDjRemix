package com.vexstudio.mashup.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.vexstudio.mashup.MainActivity
import com.vexstudio.mashup.R
import com.vexstudio.mashup.audio.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Task {
    data object Install : Task
    data object Remove : Task
    data class Import(val slot: Int, val uri: Uri) : Task
    data class Create(val a: Imported, val b: Imported, val mode: Mode, val threads: Int) : Task
    data class Preview(val project: Project) : Task
    data class Share(val project: Project, val quality: WavQuality) : Task
    data class Export(val project: Project, val quality: WavQuality, val uri: Uri) : Task
    data class Clean(val keep: Set<String>) : Task
}

sealed interface Result {
    data class Song(val slot: Int, val imported: Imported) : Result
    data class Created(val project: Project) : Result
    data class Audio(val file: File, val stats: RenderStats, val share: Boolean) : Result
    data object Done : Result
    data object Cancelled : Result
    data class Failed(val code: String) : Result
}

data class JobState(val progress: Progress? = null, val result: Result? = null, val busy: Boolean = false)

object StudioJobs {
    private val mutable = MutableStateFlow(JobState())
    val state = mutable.asStateFlow()
    private var pending: Task? = null
    private var token: Cancellation? = null

    @Synchronized fun start(context: Context, task: Task) {
        if (mutable.value.busy) return
        pending = task
        token = Cancellation()
        mutable.value = JobState(busy = true)
        try {
            ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
        } catch (error: Exception) {
            finish(Result.Failed("BACKGROUND"))
        }
    }

    @Synchronized fun take(): Pair<Task, Cancellation>? {
        val task = pending ?: return null
        pending = null
        return task to checkNotNull(token)
    }

    @Synchronized fun cancel() { token?.cancel() }
    fun update(progress: Progress) { mutable.value = JobState(progress, busy = true) }

    @Synchronized fun finish(result: Result) {
        pending = null
        token = null
        mutable.value = JobState(result = result)
    }

    @Synchronized fun acknowledge(result: Result) {
        if (mutable.value.result === result) mutable.value = JobState()
    }
}

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: Job? = null
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.processing), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            StudioJobs.cancel()
            return START_NOT_STICKY
        }
        val (task, cancel) = StudioJobs.take() ?: run {
            if (current?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 35 && task !is Task.Install) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(null), type)
            else startForeground(ID, notification(null))
        } catch (failure: Exception) {
            StudioJobs.finish(Result.Failed("BACKGROUND"))
            stopSelf()
            return START_NOT_STICKY
        }
        current = scope.launch {
            var last = 0L
            var lastStage: Stage? = null
            val report: (Progress) -> Unit = { progress ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastStage != progress.stage || now - last >= 300 || progress.total > 0 && progress.done >= progress.total) {
                    StudioJobs.update(progress)
                    notifications.notify(ID, notification(progress))
                    last = now
                    lastStage = progress.stage
                }
            }
            val result = try {
                run(task, cancel, report)
            } catch (error: CancellationException) {
                Result.Cancelled
            } catch (error: OutOfMemoryError) {
                Result.Failed("MEMORY")
            } catch (error: Exception) {
                android.util.Log.w("VexStudio", "Processing failed (${error.javaClass.simpleName})")
                val code = error.message?.takeIf { it.matches(Regex("[A-Z_]{3,32}")) }
                    ?: when {
                        task is Task.Install -> "DOWNLOAD"
                        task is Task.Import -> "INVALID_AUDIO"
                        error is IOException -> "STORAGE"
                        error is ai.onnxruntime.OrtException -> "INFERENCE"
                        else -> "PROCESS_FAILED"
                    }
                Result.Failed(code)
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                StudioJobs.finish(result)
            }
        }
        return START_NOT_STICKY
    }

    private fun run(task: Task, cancel: Cancellation, report: (Progress) -> Unit): Result {
        val files = StudioFiles(this)
        return when (task) {
            Task.Install -> {
                report(Progress(Stage.DOWNLOAD))
                files.models.install(cancel) { done, total -> report(Progress(Stage.DOWNLOAD, done, total)) }
                Result.Done
            }
            Task.Remove -> {
                val model = files.models.file
                check(!model.exists() || model.delete()) { "STORAGE" }
                Result.Done
            }
            is Task.Import -> Result.Song(task.slot, files.import(task.uri, cancel, report))
            is Task.Create -> Result.Created(files.create(task.a, task.b, task.mode, task.threads, cancel, report))
            is Task.Preview -> files.wav(task.project, WavQuality(44100, 16), cancel, report).let { Result.Audio(it.first, it.second, false) }
            is Task.Share -> files.wav(task.project, task.quality, cancel, report).let { Result.Audio(it.first, it.second, true) }
            is Task.Export -> {
                val (mixed, _) = files.render(task.project, cancel, report)
                report(Progress(Stage.EXPORT))
                try {
                    val output = contentResolver.openOutputStream(task.uri, "w") ?: error("STORAGE")
                    output.buffered().use {
                        WavOutput.write(mixed, it, task.quality, cancel::check) { done, total -> report(Progress(Stage.EXPORT, done, total)) }
                    }
                } catch (failure: Throwable) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, task.uri) }
                    throw failure
                }
                Result.Done
            }
            is Task.Clean -> {
                report(Progress(Stage.CLEAN))
                cancel.check()
                files.clean(task.keep)
                Result.Done
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        StudioJobs.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (current?.isActive == true && StudioJobs.state.value.busy) StudioJobs.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(progress: Progress?): Notification {
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vex)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(progress?.let { stageLabel(it.stage) } ?: getString(R.string.processing))
            .setContentIntent(activity)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
        if (progress != null && progress.total > 0) builder.setProgress(100, (100 * progress.done / progress.total).toInt().coerceIn(0, 100), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private companion object {
        const val CHANNEL = "audio_processing"
        const val CANCEL = "com.vexstudio.mashup.CANCEL"
        const val ID = 41
    }
}

fun Context.stageLabel(stage: Stage): String = resources.getStringArray(R.array.stages)[stage.ordinal]
