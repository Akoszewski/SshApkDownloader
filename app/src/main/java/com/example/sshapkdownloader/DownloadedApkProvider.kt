package com.example.sshapkdownloader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

class DownloadedApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val fileName = uri.lastPathSegment.orEmpty()
        if (fileName.endsWith(".apk", ignoreCase = true)) {
            return APK_MIME_TYPE
        }

        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: DEFAULT_FILE_MIME_TYPE
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Only read access is supported" }
        val apkName = uri.lastPathSegment ?: error("Missing filename")
        RemoteFileNameValidator.requireValid(apkName)

        val file = File(downloadsDirectory(), apkName)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val apkName = uri.lastPathSegment ?: error("Missing filename")
        RemoteFileNameValidator.requireValid(apkName)
        val file = File(downloadsDirectory(), apkName)

        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> apkName
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun downloadsDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SshApkDownloader"
        )
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DEFAULT_FILE_MIME_TYPE = "application/octet-stream"
    }
}
