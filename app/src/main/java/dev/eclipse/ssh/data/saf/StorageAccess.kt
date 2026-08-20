package dev.eclipse.ssh.data.saf

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

object StorageAccess {
    fun openFile(mimeType: String = "*/*"): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
    }

    fun createFile(fileName: String, mimeType: String = "application/octet-stream"): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, fileName)
    }

    fun openFolder(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun appDetails(packageName: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = "package:$packageName".toUri()
    }
}
