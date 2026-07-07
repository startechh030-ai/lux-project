package com.arena.texturepaint

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    fun copyUriToCache(context: Context, uri: Uri, fileNameHint: String = "import.glb"): File {
        val cleanName = fileNameHint.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "import.glb" }
        val out = File(context.cacheDir, cleanName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }
}
