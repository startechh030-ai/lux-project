package luxe.texture3d.app

class AssimpBridge {
    external fun nativeVersion(): String
    /** Returns an empty string on success, otherwise a user-readable error. */
    external fun nativeConvertToGltf(sourcePath: String, outputDirectory: String): String
    external fun nativeCancel()

    companion object {
        init { System.loadLibrary("luxe_assimp") }
    }
}
