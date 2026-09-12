package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.internal.MpfrBridge

/**
 * Flag classification for exceptional conditions: six sticky bits, global
 * per process (per-thread when built `--enable-thread-safe`, which the shim build
 * requires; asserted through [MpfrBridge.buildoptTls] in the native tests).
 *
 * Enum order mirrors the MPFR bits (mpfr.h L77–L82: UNDERFLOW=1, OVERFLOW=2,
 * NAN=4, INEXACT=8, ERANGE=16, DIVBY0=32) — C bit == `1 shl ordinal`.
 */
public enum class MpfrExceptionFlag {
    UNDERFLOW,
    OVERFLOW,
    NAN,
    INEXACT,
    ERANGE,
    DIVBY0,
    ;

    /** The `mpfr_flags_t` bit (== `1 shl ordinal`). */
    @InternalMpfrApi
    internal val mpfrBit: Int get() = 1 shl ordinal
}

/**
 * Bit-set over [MpfrExceptionFlag] for bulk `test`/`clear`/`set`; MPFR numbering.
 */
@JvmInline
public value class MpfrFlagsMask @InternalMpfrApi internal constructor(internal val bits: Int) {

    public val isEmpty: Boolean get() = bits == 0

    /** Whether [flag] is part of this mask. */
    public operator fun contains(flag: MpfrExceptionFlag): Boolean = bits and flag.mpfrBit != 0

    /** Union. */
    public operator fun plus(other: MpfrFlagsMask): MpfrFlagsMask = MpfrFlagsMask(bits or other.bits)

    /** Intersection. */
    public infix fun and(other: MpfrFlagsMask): MpfrFlagsMask = MpfrFlagsMask(bits and other.bits)

    /** Complement within the six defined flags (`MPFR_FLAGS_ALL`). */
    public fun invert(): MpfrFlagsMask = MpfrFlagsMask(bits xor ALL_BITS)

    override fun toString(): String = MpfrExceptionFlag.entries.filter { contains(it) }.toString()

    public companion object {
        /** The empty mask. */
        public val NONE: MpfrFlagsMask = MpfrFlagsMask(0)

        /** All six flags (`MPFR_FLAGS_ALL`). */
        public val ALL: MpfrFlagsMask = MpfrFlagsMask(ALL_BITS)

        /** Builds a mask containing exactly [flags]. */
        public fun of(vararg flags: MpfrExceptionFlag): MpfrFlagsMask {
            var bits = 0
            for (flag in flags) bits = bits or flag.mpfrBit
            return MpfrFlagsMask(bits)
        }

        internal const val ALL_BITS: Int = 0b111_111
    }
}

/**
 * Immutable snapshot of MPFR exception flags: a set-like value object.
 * Companion `current()/save()` read through the per-op-isolated view;
 * the `clear`/`set` family acts on the MPFR global.
 */
public class MpfrExceptionFlags private constructor(internal val bits: Int) {

    /** The raised flags as a set. */
    public val flags: Set<MpfrExceptionFlag>
        get() = MpfrExceptionFlag.entries.filterTo(mutableSetOf()) { bits and it.mpfrBit != 0 }

    public val underflow: Boolean get() = bits and MpfrExceptionFlag.UNDERFLOW.mpfrBit != 0
    public val overflow: Boolean get() = bits and MpfrExceptionFlag.OVERFLOW.mpfrBit != 0

    /** The raised-`NAN`-flag case; independent of a *value* being NaN. */
    public val nan: Boolean get() = bits and MpfrExceptionFlag.NAN.mpfrBit != 0
    public val inexact: Boolean get() = bits and MpfrExceptionFlag.INEXACT.mpfrBit != 0
    public val erange: Boolean get() = bits and MpfrExceptionFlag.ERANGE.mpfrBit != 0
    public val divideByZero: Boolean get() = bits and MpfrExceptionFlag.DIVBY0.mpfrBit != 0

    /** How many flags are set (0..6). */
    public val size: Int get() = bits.let { b ->
        var count = 0
        for (flag in MpfrExceptionFlag.entries) if (b and flag.mpfrBit != 0) count++
        count
    }

    public fun isEmpty(): Boolean = bits == 0

    /** Structural equality (value semantics). */
    override fun equals(other: Any?): Boolean =
        other is MpfrExceptionFlags && other.bits == bits

    override fun hashCode(): Int = bits

    override fun toString(): String = "MpfrExceptionFlags$flags"

    public companion object {
        /** All bits false. */
        public val NONE: MpfrExceptionFlags = MpfrExceptionFlags(0)

        /** Current global flags — per-op isolation guarantees this never contains another thread's delta. */
        public fun current(): MpfrExceptionFlags = MpfrExceptionFlags(MpfrBridge.flagsGet().toInt())

        /**
         * Bulk query against the **global set**: `(current() and mask)` via
         * `mpfr_flags_test`.
         */
        public fun test(mask: MpfrFlagsMask): MpfrExceptionFlags =
            MpfrExceptionFlags(current().bits and mask.bits)

        /** Opaque token for [MpfrFlagsSnapshot.restore]. */
        public fun save(): MpfrFlagsSnapshot = MpfrFlagsSnapshot(MpfrBridge.flagsSave())

        /** `mpfr_clear_flags` — resets all six bits. */
        public fun clearAll(): Unit = MpfrBridge.flagsClear(MpfrFlagsMask.ALL.bits.toUInt())

        /** Resets one bit (`mpfr_clear_*`). */
        public fun clear(flag: MpfrExceptionFlag): Unit = MpfrBridge.flagsClear(flag.mpfrBit.toUInt())

        /** Sets one bit (`mpfr_set_*`); rarely needed by user code. */
        public fun set(flag: MpfrExceptionFlag): Unit = MpfrBridge.flagsSet(flag.mpfrBit.toUInt())
    }
}

/** Restorable flags token (`mpfr_flags_save` with `mpfr_flags_restore`). */
@JvmInline
public value class MpfrFlagsSnapshot @InternalMpfrApi internal constructor(private val raw: UInt) {
    /** The saved `mpfr_flags_t` state (raw bits). */
    @InternalMpfrApi
    internal val mpfrBits: UInt get() = raw

    /** Writes the saved state back to the global set, then clears [clear]'s bits. */
    public fun restore(clear: MpfrFlagsMask = MpfrFlagsMask.NONE): Unit =
        MpfrBridge.flagsRestore(raw, clear.bits.toUInt())
}
