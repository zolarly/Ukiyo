package io.github.zolarly.ukiyo.net

import java.io.IOException

enum class Fix { RETRY, LOGIN, NONE }

data class ErrorInfo(val message: String, val fix: Fix)

fun describeError(e: Throwable): ErrorInfo = when (e) {
    is ApiException -> when (e.code) {
        401 -> ErrorInfo("登录已失效，请重新登录", Fix.LOGIN)
        403, 404 -> ErrorInfo("链接已销毁", Fix.NONE)
        410 -> ErrorInfo("查看次数已用完", Fix.NONE)
        413 -> ErrorInfo("文件太大，超过了服务器的上限", Fix.NONE)
        429 -> ErrorInfo("今日上传额度已用完", Fix.NONE)
        507 -> ErrorInfo("服务器空间不足，请稍后再试", Fix.RETRY)
        else -> ErrorInfo("服务器错误 ${e.code}：${e.message}", Fix.RETRY)
    }
    is IOException -> ErrorInfo("网络问题：${e.message ?: e.javaClass.simpleName}", Fix.RETRY)
    else -> ErrorInfo("出错了：$e", Fix.RETRY)
}
