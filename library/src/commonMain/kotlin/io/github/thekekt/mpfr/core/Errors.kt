package io.github.thekekt.mpfr.core

/**
 * Marker for expected failures carried by [Result].
 * Programmer errors throw `…Exception` types instead and never use this hierarchy.
 */
public interface MpfrError

/**
 * Expected parse failure. The three variants map this library's parse status codes,
 * derived from `mpfr_strtofr`'s end-pointer and the base pre-check (the C function
 * itself returns the usual ternary value): -1 → [MalformedInput], -2 → [UnsupportedBase],
 * -3 → [ExponentSyntax]; status 0 never produces an error value.
 */
public sealed interface MpfrParseError : MpfrError {
    /** Malformed or empty input (library status -1). */
    public data object MalformedInput : MpfrParseError

    /** `base` outside the MPFR-supported range [2, 62] (library status -2). */
    public data object UnsupportedBase : MpfrParseError

    /** Exponent-part syntax error (library status -3). */
    public data object ExponentSyntax : MpfrParseError
}

/**
 * Expected narrowing failure. The only non-parse error type sanctioned in `core`;
 * it enters the public surface only when a `Result`-returning narrowing
 * (`toLongResult`) is adopted — kept here as the taxonomy slot holder.
 */
public data object MpfrRangeError : MpfrError
