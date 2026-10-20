package ir.solina.fm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

class FileTransferService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "solina_file_transfers"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "ir.solina.fm.START_TRANSFER"
        const val ACTION_CANCEL = "ir.solina.fm.CANCEL_TRANSFER"
        const val EXTRA_SOURCES = "extra_sources"
        const val EXTRA_DEST = "extra_dest"
        const val EXTRA_IS_MOVE = "extra_is_move"

        fun startTransfer(
            context: Context,
            sources: List<File>,
            destination: File,
            isMove: Boolean
        ) {
            val intent = Intent(context, FileTransferService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCES, sources.map { it.absolutePath }.toTypedArray())
                putExtra(EXTRA_DEST, destination.absolutePath)
                putExtra(EXTRA_IS_MOVE, isMove)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            serviceScope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val paths = intent?.getStringArrayExtra(EXTRA_SOURCES) ?: emptyArray()
        val destPath = intent?.getStringExtra(EXTRA_DEST) ?: ""
        val isMove = intent?.getBooleanExtra(EXTRA_IS_MOVE, false) ?: false

        if (paths.isEmpty() || destPath.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val sources = paths.map { File(it) }
        val destination = File(destPath)

        val notification = buildNotification("شروع عملیات...", 0, 100)
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            var completionMessage: String? = null
            try {
                val result = FileOperationsManager.executeTransfer(sources, destination, isMove) { progress ->
                    val updatedNotif = buildNotification(
                        "در حال انتقال: ${progress.currentFileName} (${progress.processedCount}/${progress.totalCount})",
                        progress.percent,
                        100
                    )
                    notificationManager.notify(NOTIFICATION_ID, updatedNotif)
                }
                val summary = "تمام شد: ${result.successCount} موفق، ${result.failureCount} ناموفق"
                val detail = result.errors.take(3).joinToString("؛ ")
                completionMessage = if (detail.isBlank()) summary else "$summary — $detail"
            } catch (cancelled: CancellationException) {
                // Cancellation is expected when the user/system stops the service.
                throw cancelled
            } catch (error: Exception) {
                completionMessage = "انتقال ناموفق بود: ${error.localizedMessage ?: "خطای ناشناخته"}"
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    completionMessage?.let { message ->
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(message, 100, 100, ongoing = false)
                        )
                    }
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(text: String, current: Int, max: Int, ongoing: Boolean = true) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file)
            .setContentTitle("Solina Files — عملیات انتقال")
            .setContentText(text)
            .setProgress(max, current, false)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "عملیات انتقال فایل Solina Files",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش پیشرفت کپی و انتقال فایل‌های حجیم"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
