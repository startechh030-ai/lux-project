package luxe.texture3d.app

import android.content.Context
import java.io.File

/** Canonical app-specific storage. Never construct /storage/emulated/0 paths manually. */
class AppFileSystem(context: Context) {
    val root: File = requireNotNull(context.getExternalFilesDir(null)) { "External app storage unavailable" }
    val imports = File(root, "imports")
    val importHistory = File(imports, "history")
    val assets = File(root, "assets") // legacy model assets; migrated into Element registry
    val library = File(root, "library")
    val elements = File(library, "elements")
    val blobs = File(library, "blobs/sha256")
    val libraryTrash = File(library, "trash")
    val projects = File(root, "projects")
    val staging = File(root, "staging")
    val runtimeCache = File(context.externalCacheDir ?: context.cacheDir, "runtime")
    val thumbnails = File(root, "thumbnails")

    init {
        listOf(imports, importHistory, assets, library, elements, blobs, libraryTrash, projects, staging, runtimeCache, thumbnails).forEach { dir ->
            check(dir.exists() || dir.mkdirs()) { "Unable to create ${dir.absolutePath}" }
        }
    }

    fun recoverStaging() {
        staging.listFiles()?.forEach { entry ->
            if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
        }
        // Transactions are hidden from the Asset Library and are safe to
        // remove after process death because only validated assets are renamed.
        assets.listFiles()?.filter { it.name.startsWith(".converting-") }?.forEach { it.deleteRecursively() }
    }
}
