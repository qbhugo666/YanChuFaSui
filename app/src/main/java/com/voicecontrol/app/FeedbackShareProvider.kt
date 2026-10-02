package com.voicecontrol.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** 只读分享用户主动导出的反馈。不是通用文件接口，不暴露filesDir、模型或录音。
 * 每次导出用不同随机文件名，旧分享授权不能读取下次的反馈；48h失效。 */
class FeedbackShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    private fun reportFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val parts = uri.pathSegments
        if (uri.authority != "${ctx.packageName}.feedback" || parts.size != 1 ||
            !FeedbackSharePolicy.validFilename(parts[0])) throw FileNotFoundException()
        val dir = File(ctx.cacheDir, "speech_feedback").canonicalFile
        val f = File(dir, parts[0]).canonicalFile
        if (f.parentFile != dir || !f.isFile || System.currentTimeMillis() - f.lastModified() > FeedbackSharePolicy.RETENTION_MS)
            throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(reportFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String { reportFile(uri); return "text/plain" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = reportFile(uri)
        val columns = projection?.take(8)?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> "言出法随反馈.txt"
                OpenableColumns.SIZE -> f.length()
                else -> null
            } }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
