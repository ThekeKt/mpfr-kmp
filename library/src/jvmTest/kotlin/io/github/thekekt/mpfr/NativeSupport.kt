package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.internal.MpfrBridge

/**
 * Detects whether the JNI shim actually loaded on this host. Native-dependent
 * smoke tests run inside [runNative]; when the library is absent (e.g. a Windows
 * host without a locally built shim) they log a skip instead of failing.
 * Linux/CI hosts run them for real.
 */
internal object NativeSupport {
    val available: Boolean by lazy {
        try {
            MpfrBridge.flagsGet()
            true
        } catch (t: Throwable) {
            false
        }
    }
}

/** Execute [body] only when the native shim is loaded; otherwise emit a skip line.
 *  When loaded, a non-TLS MPFR build must fail the run (thread-safe build requirement). */
internal inline fun runNative(body: () -> Unit) {
    if (!NativeSupport.available) {
        println("SKIPPED (native shim mpfr_kmp unavailable in this environment)")
        return
    }
    check(io.github.thekekt.mpfr.internal.MpfrBridge.buildoptTls()) {
        "MPFR build must expose mpfr_buildopt_tls_p() == true (thread-safe build)"
    }
    body()
}
