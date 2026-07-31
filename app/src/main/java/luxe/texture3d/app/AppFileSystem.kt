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
    val libraryTextures = File(library, "Texture")
    val libraryAudio = File(library, "Audio")
    val libraryVideo = File(library, "Video")
    val libraryEnvironment = File(library, "Environment")
    val libraryHdri = File(libraryEnvironment, "HDRI")
    val libraryExr = File(libraryEnvironment, "EXR")
    val libraryKtx2 = File(libraryEnvironment, "KTX2")
    val libraryAnimation = File(library, "Animation")
    val libraryScript = File(library, "Script")
    val elements = File(library, "Element")
    val blobs = File(library, "blobs/sha256")
    val libraryTrash = File(library, "Trash")
    val projects = File(root, "projects")
    val staging = File(root, "staging")
    val runtimeCache = File(context.externalCacheDir ?: context.cacheDir, "runtime")
    val thumbnails = File(root, "thumbnails")

    init {
        listOf(imports, importHistory, assets, library, libraryTextures, libraryAudio, libraryVideo, libraryEnvironment, libraryHdri, libraryExr, libraryKtx2, libraryAnimation, libraryScript, elements, blobs, libraryTrash, projects, staging, runtimeCache, thumbnails).forEach { dir ->
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
