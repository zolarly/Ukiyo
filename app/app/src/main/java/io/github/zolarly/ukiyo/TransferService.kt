package io.github.zolarly.ukiyo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.github.zolarly.ukiyo.net.Api
import io.github.zolarly.ukiyo.net.describeError
import io.github.zolarly.ukiyo.send.UploadState
import io.github.zolarly.ukiyo.send.describe
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全局的上传状态。服务负责写，发送页负责读。 */
object Transfers {
    val upload = MutableStateFlow<UploadState>(UploadState.Idle)
    val busy get() = upload.value is UploadState.Uploading
}

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "上传进度", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, progress(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val clip = intent?.clipData
        val maxViews = intent?.getIntExtra(EXTRA_MAX_VIEWS, 0) ?: 0
        when {
            job?.isActive == true -> {}
            clip == null -> stopSelf()
            else -> job = scope.launch {
                val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                val result = upload(uris, maxViews)
                Transfers.upload.value = result
                // 进度通知更新太快会被系统限流丢掉，换一个新通知显示结果
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifications.notify(ID + 1, finished(result))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 起，dataSync 前台服务每天最多运行 6 小时，到时必须自己停下
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        Transfers.upload.value = UploadState.Failed(IOException("后台上传超过了系统时限"))
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun upload(uris: List<Uri>, maxViews: Int): UploadState = try {
        val files = withContext(Dispatchers.IO) { uris.map { contentResolver.describe(it) } }
        var shown = -1
        val share = Api.createShare(contentResolver, files, maxViews) { sent, total ->
            // 取消后 OkHttp 线程可能还会回调一次，不能把 Failed 改回 Uploading
            Transfers.upload.update { if (it is UploadState.Uploading) UploadState.Uploading(sent, total) else it }
            // 有文件大小未知时 sent 会超过 total
            val percent = if (total > 0) (sent * 100 / total).toInt().coerceAtMost(100) else 0
            if (percent != shown) {
                shown = percent
                notifications.notify(ID, progress(percent))
            }
        }
        UploadState.Done(share)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UploadState.Failed(e)
    }

    private fun builder(): NotificationCompat.Builder {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
    }

    private fun progress(percent: Int) = builder()
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("正在上传 $percent%")
        .setProgress(100, percent, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun finished(result: UploadState) = builder()
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle(if (result is UploadState.Done) "上传完成" else "上传失败")
        .setContentText(if (result is UploadState.Failed) describeError(result.error).message else "链接在「我发出的」里")
        .setAutoCancel(true)
        .build()

    companion object {
        private const val CHANNEL = "transfer"
        private const val ID = 1
        private const val EXTRA_MAX_VIEWS = "max_views"

        /** 把 Uri 放进 ClipData 并带上读取授权，这样分享进来的页面关闭后，服务仍然能读这些文件。 */
        fun start(context: Context, uris: List<Uri>, maxViews: Int) {
            val clip = ClipData.newRawUri(null, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val intent = Intent(context, TransferService::class.java).apply {
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_MAX_VIEWS, maxViews)
            }
            context.startForegroundService(intent)
        }
    }
}
