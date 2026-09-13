package io.github.thekekt.mpfr.examples

import io.github.thekekt.mpfr.MpfrFloat

/**
 * Whether the MPFR native backend is usable on this host right now. The harness
 * keeps working (compile-only) on hosts without a built shim — e.g. a Windows
 * checkout before the DLL packaging work lands. Any backend failure counts:
 * a failing bridge class-initialization surfaces as `ExceptionInInitializerError`
 * / `NoClassDefFoundError` rather than a load exception.
 */
public object ExamplesRuntime {

    public val mpfrAvailable: Boolean by lazy { probe() }

    private fun probe(): Boolean = try {
        MpfrFloat.of(1L)
        true
    } catch (_: Throwable) {
        false
    }
}
