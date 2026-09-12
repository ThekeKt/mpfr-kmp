package io.github.thekekt.mpfr

/**
 * Reverse-operand mixed arithmetic (`k + x`). These are
 * **plain extension operators** in the library package only: never available via a
 * wildcard-safe import to unrelated code paths, and result precision is the
 * `MpfrFloat` operand's precision (the scalar crosses the bridge as an exact value).
 */
public operator fun Long.plus(other: MpfrFloat): MpfrFloat = other + this
public operator fun Long.minus(other: MpfrFloat): MpfrFloat = other.unaryMinus() + this
public operator fun Long.times(other: MpfrFloat): MpfrFloat = other * this

public operator fun Double.plus(other: MpfrFloat): MpfrFloat = other + this
public operator fun Double.minus(other: MpfrFloat): MpfrFloat = other.unaryMinus() + this
public operator fun Double.times(other: MpfrFloat): MpfrFloat = other * this

public operator fun Int.plus(other: MpfrFloat): MpfrFloat = other + this.toLong()
public operator fun Int.minus(other: MpfrFloat): MpfrFloat = (-other).add(this.toLong())
public operator fun Int.times(other: MpfrFloat): MpfrFloat = other * this.toLong()
