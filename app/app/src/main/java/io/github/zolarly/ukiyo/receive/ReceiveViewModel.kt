package io.github.zolarly.ukiyo.receive

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zolarly.ukiyo.net.Api
import io.github.zolarly.ukiyo.net.RemoteFile
import io.github.zolarly.ukiyo.net.ShareInfo
import io.github.zolarly.ukiyo.net.ShareLink
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Item(
    val file: RemoteFile,
    val received: Long = 0,
    val done: Boolean = false,
    val thumb: Bitmap? = null,
    val saved: Boolean = false,
)

sealed interface ReceiveState {
    data object Loading : ReceiveState
    data class Ready(val info: ShareInfo, val items: List<Item>, val opened: Boolean = false) : ReceiveState
    data class Failed(val error: Throwable) : ReceiveState
}

class ReceiveViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<ReceiveState>(ReceiveState.Loading)
    val state: StateFlow<ReceiveState> = _state

    private val cacheRoot = File(app.cacheDir, "receive")
    private var link: ShareLink? = null
    private var info: ShareInfo? = null
    private var urls: List<String>? = null
    private var job: Job? = null
    private val savedIndexes = mutableSetOf<Int>()

    fun open(link: ShareLink) {
        this.link = link
        info = null
        urls = null
        savedIndexes.clear()
        start { load(link) }
    }

    fun view() {
        // 连点两下时，第一次的 open 可能已经扣了次数，不能取消它再发一次。
        if (job?.isActive == true) return
        val link = link ?: return
        val info = info ?: return
        start {
            val urls = this.urls ?: Api.open(link).also { this.urls = it }
            download(link, info, urls)
        }
    }

    fun retry() {
        val link = link ?: return
        if (urls != null) view() else start { load(link) }
    }

    fun saveAll() {
        val link = link ?: return
        val s = _state.value as? ReceiveState.Ready ?: return
        if (job?.isActive == true) return
        val dir = File(cacheRoot, link.token)
        val resolver = getApplication<Application>().contentResolver
        start {
            s.items.forEachIndexed { i, item ->
                if (item.saved) return@forEachIndexed
                val file = File(dir, i.toString())
                withContext(Dispatchers.IO) {
                    saveToGallery(resolver, file, item.file.name, item.file.type)
                    file.delete()
                }
                savedIndexes += i
                updateItem(i) { it.copy(saved = true) }
            }
        }
    }

    /** 离开接收页时调用：停止下载，删除这次的缓存。 */
    fun close() {
        job?.cancel()
        link?.let { File(cacheRoot, it.token).deleteRecursively() }
        link = null
        info = null
        urls = null
        savedIndexes.clear()
        _state.value = ReceiveState.Loading
    }

    override fun onCleared() = close()

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ReceiveState.Failed(e)
            }
        }
    }

    private suspend fun load(link: ShareLink) {
        _state.value = ReceiveState.Loading
        val info = Api.getShare(link)
        this.info = info
        _state.value = ReceiveState.Ready(info, info.files.map { Item(it) })
    }

    private suspend fun download(link: ShareLink, info: ShareInfo, urls: List<String>) {
        val dir = File(cacheRoot, link.token)
        withContext(Dispatchers.IO) { dir.mkdirs() }
        _state.value = ReceiveState.Ready(
            info,
            info.files.mapIndexed { i, f -> Item(f, done = i in savedIndexes, saved = i in savedIndexes) },
            opened = true,
        )
        urls.forEachIndexed { i, url ->
            if (i in savedIndexes) return@forEachIndexed
            val remote = info.files[i]
            val file = File(dir, i.toString())
            if (file.length() > remote.size) file.delete()
            if (file.length() < remote.size) {
                Api.download(url, file) { n -> updateItem(i) { it.copy(received = n) } }
            }
            if (file.length() != remote.size) {
                file.delete()
                throw IOException("${remote.name} 下载不完整")
            }
            val thumb = withContext(Dispatchers.IO) { thumbnail(file, remote.type) }
            updateItem(i) { it.copy(received = remote.size, done = true, thumb = thumb) }
        }
    }

    private fun updateItem(i: Int, change: (Item) -> Item) {
        _state.update { s ->
            if (s is ReceiveState.Ready) s.copy(items = s.items.toMutableList().also { it[i] = change(it[i]) }) else s
        }
    }
}
