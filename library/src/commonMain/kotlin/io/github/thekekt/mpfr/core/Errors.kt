package io.github.thekekt.mpfr.core

/**
 * Marker for expected failures carried by [Result].
 * Programmer errors throw `…Exception` types instead and never use this hierarchy.
 */
public interface MpfrError

/**
 * Expected parse failure. The three variants map the
 * `mpfr_strtofr` status codes: -1 → [MalformedInput], -2 → [UnsupportedBase],
 * -3 → [ExponentSyntax]; status 0 never produces an error value.
 */
public sealed interface MpfrParseError : MpfrError {
    /** Malformed or empty input (MPFR status -1). */
    public data object MalformedInput : MpfrParseError

    /** `base` outside the MPFR-supported range [2, 62] (MPFR status -2). */
    public data object UnsupportedBase : MpfrParseError

    /** Exponent-part syntax error (MPFR status -3). */
    public data object ExponentSyntax : MpfrParseError
}

/**
 * Expected narrowing failure. The only non-parse error type sanctioned in `core`;
 * it enters the public surface only when a `Result`-returning narrowing
 * (`toLongResult`) is adopted — kept here as the taxonomy slot holder.
 */
public data object MpfrRangeError : MpfrError
