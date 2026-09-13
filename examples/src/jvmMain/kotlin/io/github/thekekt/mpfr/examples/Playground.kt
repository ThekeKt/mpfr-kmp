package io.github.thekekt.mpfr.examples

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.MpfrLoadException

/**
 * Manual exploration entry point: run with `-Dmpfrkmp.native.dir=<dir with
 * libmpfr_kmp.so>` (after `library/native/jni/build-shim.sh` on Linux/WSL), or
 * `-Dmpfrkmp.native.allowDevGuess=true` to let the loader find
 * `library/native/build` relative to the working directory. Prints a handful of
 * correctly-rounded results at several precisions.
 */
public fun main() {
    if (!ExamplesRuntime.mpfrAvailable) {
        println(
            "The native MPFR backend is unavailable here. Build the shim with " +
                "bash library/native/jni/build-shim.sh (Linux/WSL) and set " +
                "-Dmpfrkmp.native.dir (or place libmpfr_kmp.so on java.library.path).",
        )
        return
    }
    try {
        val pi128 = MpfrFloat.PI(MpfrPrecision(128))
        println("pi(128)  = $pi128")
        println("pi(256)*2 = " + MpfrFloat.PI(MpfrPrecision(256)).mul(2L))
        val x = MpfrFloat.parse("1.1010001111", MpfrPrecision(64), base = 2)
        println("parse(bin 1.1010001111) = " + (x as io.github.thekekt.mpfr.core.Result.Success).value.toString(10))
        println("sqrt(2) @ 64 = " + MpfrFloat.of(2L).sqrt())
    } catch (e: MpfrLoadException) {
        println("Backend failed mid-run: ${e.message}")
    }
}
