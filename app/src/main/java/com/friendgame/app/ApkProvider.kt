package com.friendgame.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Entrega el propio APK de la app para pasárselo a un amigo por Quick Share, Bluetooth o
 * WhatsApp. Solo lectura y solo para la app que reciba el permiso puntual al compartir.
 */
class ApkProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.friendgame.app.apk"
        const val FILE_NAME = "FriendGame.apk"
        const val MIME = "application/vnd.android.package-archive"
        val URI: Uri = Uri.parse("content://$AUTHORITY/$FILE_NAME")
    }

    private fun apk(): File {
        val path = context?.applicationInfo?.sourceDir ?: throw FileNotFoundException(FILE_NAME)
        return File(path)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment != FILE_NAME) throw FileNotFoundException(uri.toString())
        if (mode != "r") throw SecurityException("Solo lectura")
        return ParcelFileDescriptor.open(apk(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> FILE_NAME
                OpenableColumns.SIZE -> apk().length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
