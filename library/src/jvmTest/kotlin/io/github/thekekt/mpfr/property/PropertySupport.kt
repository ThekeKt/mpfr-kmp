package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.core.MpfrParseError
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.internal.MpfrBridge
import io.kotest.property.PropTestConfig

/**
 * Property-tier helpers: native availability gating (the same skip convention as
 * the smoke/golden tiers), a shared deterministic [PropTestConfig], and
 * value-equality assertions that keep the NaN identity and the numeric
 * (mpfr-style) equality distinct.
 */

/** Emits the standard skip line and returns true when the shim is absent; otherwise
 *  asserts the thread-safe build and returns false. */
internal inline fun skipIfNativeMissing(): Boolean {
    if (!io.github.thekekt.mpfr.NativeSupport.available) {
        println("SKIPPED (native shim mpfr_kmp unavailable in this environment)")
        return true
    }
    check(MpfrBridge.buildoptTls()) {
        "MPFR build must expose mpfr_buildopt_tls_p() == true (thread-safe build)"
    }
    return false
}

/** Shared configuration: fixed seed (deterministic runs) and bounded failure count. */
internal val PROPS = PropTestConfig(seed = 20260929L, iterations = 300, maxFailure = 1)

internal val PROPS_LONG = PropTestConfig(seed = 20260929L, iterations = 1_000, maxFailure = 1)

/** Cheap configuration for the expensive widths. */
internal val PROPS_HEAVY = PropTestConfig(seed = 20260929L, iterations = 60, maxFailure = 1)

/** Structural equality (precision-aware, NaN-aware). */
internal fun assertSameValue(expected: MpfrFloat, actual: MpfrFloat, hint: () -> String = { "" }) {
    if (expected.isNaN) {
        check(actual.isNaN) { "expected NaN, got $actual ${hint()}" }
        check(expected.precision == actual.precision) {
            "expected NaN at ${expected.precision}, got ${actual.precision} ${hint()}"
        }
        return
    }
    check(expected == actual) { "expected $expected, got $actual ${hint()}" }
}

/** Unwraps a parse result, failing the test on a typed parse error. */
internal fun Result<MpfrFloat, MpfrParseError>.valueOrFail(): MpfrFloat = when (this) {
    is Result.Success -> value
    is Result.Failure -> error("parse unexpectedly failed: $error")
}

/**
 * Numeric value equality, the `mpfr_equal_p` view: NaN matches NaN; ±0 match;
 * numeric equality ignores the precision. Deliberately distinct from [assertSameValue].
 */
internal fun assertNumericSame(expected: MpfrFloat, actual: MpfrFloat, hint: () -> String = { "" }) {
    if (expected.isNaN || actual.isNaN) {
        check(expected.isNaN && actual.isNaN) { "expected $expected, got $actual ${hint()}" }
        return
    }
    check(!expected.lt(actual) && !actual.lt(expected)) {
        "expected $expected, got $actual ${hint()}"
    }
}