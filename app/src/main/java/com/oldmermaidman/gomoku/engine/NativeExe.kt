package com.oldmermaidman.gomoku.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates the Rapfi binary and extracts its data files (NNUE weights + config).
 *
 * Android 10+ (API 29) forbids `exec()` on files inside the app's writable home
 * directory (`filesDir`, SELinux label `app_data_file`) — `chmod +x` does not help.
 * The only exec-allowed location is the app's `nativeLibraryDir`, so the engine is
 * shipped as a "native library" in `jniLibs/arm64-v8a/librapfi.so` and the OS
 * extracts it there (see `android:extractNativeLibs="true"` + legacy packaging).
 *
 * The engine's data files (config.toml, classical/, mix9svq/) stay in
 * `assets/engine/` and are copied to `filesDir/engine/`, which is also set as the
 * process working directory so Rapfi finds them.
 */
class NativeExe(private val context: Context) {

    companion object {
        private const val TAG = "NativeExe"
        const val BINARY_NAME = "librapfi.so"
        private const val ASSET_ROOT = "engine"
    }

    /** Working directory for the engine process; holds config + weights. */
    val engineDir: File = File(context.filesDir, "engine")

    /** The executable, extracted by the OS into nativeLibraryDir. */
    val binaryFile: File?
        get() = context.applicationInfo.nativeLibraryDir?.let { File(it, BINARY_NAME) }

    fun isEngineInstalled(): Boolean = binaryFile?.exists() == true

    /**
     * Copies the engine data files from `assets/engine/` into `filesDir/engine/`.
     * The binary itself is already in `nativeLibraryDir` (no copy / chmod needed).
     */
    fun install(): Boolean {
        val bin = binaryFile
        if (bin == null || !bin.exists()) {
            Log.w(TAG, "engine binary missing in nativeLibraryDir")
            return false
        }
        return try {
            if (!engineDir.exists()) engineDir.mkdirs()
            copyAssetDir(ASSET_ROOT, engineDir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "engine data install failed", e)
            false
        }
    }

    /** Recursively copies an asset directory into a file-system directory. */
    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) return
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val childTarget = File(targetDir, name)

            // A subdirectory has children; a file has none (list returns empty/null).
            val children = context.assets.list(childAsset)
            if (children != null && children.isNotEmpty()) {
                childTarget.mkdirs()
                copyAssetDir(childAsset, childTarget)
            } else {
                context.assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
