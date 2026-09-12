package io.github.thekekt.mpfr.internal

import io.github.thekekt.mpfr.MpfrLoadException
import java.io.File

/**
 * Loads the `mpfr_kmp` JNI shim once. Resolution order:
 *
 *  1. `-Dmpfrkmp.native.file=<path>` env `MPFRKMP_NATIVE_FILE` — absolute dev/CI override;
 *  2. `-Dmpfrkmp.native.dir=<dir>` / env `MPFRKMP_NATIVE_DIR` — directory containing the library;
 *  3. `System.loadLibrary("mpfr_kmp")` — packaged/embedded path.
 *
 * Bundling the library per-platform inside the artifact (Maven classifiers / Android
 * `jniLibs`) is pending packaging work; the classpath-extraction path lands with it.
 * Every failure surfaces as
 * [MpfrLoadException].
 */
internal object MpfrNativeLoader {

    private const val LIBRARY_NAME = "mpfr_kmp"

    @Volatile
    private var ready = false

    fun ensureLoaded() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            try {
                val file = System.getProperty("mpfrkmp.native.file") ?: System.getenv("MPFRKMP_NATIVE_FILE")
                if (file != null) {
                    System.load(File(file).absolutePath)
                    ready = true
                    return
                }
                val dir = System.getProperty("mpfrkmp.native.dir") ?: System.getenv("MPFRKMP_NATIVE_DIR")
                if (dir != null) {
                    System.load(File(dir, platformFileName()).absolutePath)
                    ready = true
                    return
                }
                try {
                    System.loadLibrary(LIBRARY_NAME)
                } catch (first: UnsatisfiedLinkError) {
                    // one more chance: the build-dir layout used in development (`library/native/build/…`)
                    val guess = File(nativeBuildDirGuess(), platformFileName())
                    if (!guess.isFile) throw first
                    System.load(guess.absolutePath)
                }
                ready = true
            } catch (e: MpfrLoadException) {
                throw e
            } catch (e: Throwable) {
                throw MpfrLoadException(
                    "could not load native shim '$LIBRARY_NAME' (${platformFileName()}); " +
                        "set -Dmpfrkmp.native.file or build it via library/native/jni/build-shim.sh",
                    e,
                )
            }
        }
    }

    private fun nativeBuildDirGuess(): File = File("library/native/build")

    internal fun platformFileName(): String {
        val os = System.getProperty("os.name", "").lowercase()
        val prefix = if (os.contains("win")) "" else "lib"
        val suffix = when {
            os.contains("win") -> "dll"
            os.contains("mac") || os.contains("darwin") -> "dylib"
            else -> "so"
        }
        return "$prefix$LIBRARY_NAME.$suffix"
    }
}
