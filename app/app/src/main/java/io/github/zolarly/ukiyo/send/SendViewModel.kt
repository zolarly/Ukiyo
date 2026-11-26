package io.github.zolarly.ukiyo.send

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zolarly.ukiyo.TransferService
import io.github.zolarly.ukiyo.Transfers
import io.github.zolarly.ukiyo.net.CreatedShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UploadState {
    data object Idle : UploadState
    data class Uploading(val sent: Long, val total: Long) : UploadState
    data class Done(val share: CreatedShare) : UploadState
    data class Failed(val error: Throwable) : UploadState
}

class SendViewModel(app: Application) : AndroidViewModel(app) {
    private val _files = MutableStateFlow<List<LocalFile>>(emptyList())
    val files: StateFlow<List<LocalFile>> = _files
    val state: StateFlow<UploadState> = Transfers.upload
    var maxViews by mutableIntStateOf(0)

    private var uris: List<Uri> = emptyList()

    fun start(uris: List<Uri>) {
        if (uris == this.uris && state.value !is UploadState.Done) return
        this.uris = uris
        Transfers.upload.value = UploadState.Idle
        maxViews = 0
        _files.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            try {
                _files.value = uris.map { resolver.describe(it) }
            } catch (e: Exception) {
                // 分享进来的 Uri 可能没有读取授权（SecurityException）
                Transfers.upload.value = UploadState.Failed(e)
            }
        }
    }

    fun upload() {
        if (Transfers.busy || uris.isEmpty()) return
        Transfers.upload.value = UploadState.Uploading(0, _files.value.sumOf { it.size.coerceAtLeast(0) })
        try {
            TransferService.start(getApplication(), uris, maxViews)
        } catch (e: RuntimeException) {
            // 后台启动被拒是 IllegalStateException；没有文件读取授权、无法转交给服务时是 SecurityException
            Transfers.upload.value = UploadState.Failed(e)
        }
    }
}
