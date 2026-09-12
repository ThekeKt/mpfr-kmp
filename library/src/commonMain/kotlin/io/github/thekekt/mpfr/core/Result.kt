package io.github.thekekt.mpfr.core

/**
 * Project-local `Result`: expected failures are typed values (single-error rule),
 * never exceptions. Files using it never import `kotlin.Result`; the explicit
 * import of this type shadows the stdlib one. A `Result` carries exactly one error.
 */
public sealed interface Result<out D, out E : MpfrError> {

    /** Successful computation carrying [value]. */
    public data class Success<out D>(public val value: D) : Result<D, Nothing>

    /** Failed computation carrying [error]. */
    public data class Failure<out E : MpfrError>(public val error: E) : Result<Nothing, E>
}

/** Wraps [value] in [Result.Success]. */
public fun <D> success(value: D): Result<D, Nothing> = Result.Success(value)

/** Wraps [error] in [Result.Failure]. */
public fun <E : MpfrError> failure(error: E): Result<Nothing, E> = Result.Failure(error)

/** Transforms a successful value, keeping the error side untouched. */
public inline fun <D, E : MpfrError, R> Result<D, E>.map(transform: (D) -> R): Result<R, E> =
    when (this) {
        is Result.Success -> Result.Success(transform(value))
        is Result.Failure -> this
    }

/** Runs [action] with the successful value; returns `this` for chaining. */
public inline fun <D, E : MpfrError> Result<D, E>.onSuccess(action: (D) -> Unit): Result<D, E> {
    if (this is Result.Success) action(value)
    return this
}

/** Runs [action] with the failure; returns `this` for chaining. */
public inline fun <D, E : MpfrError> Result<D, E>.onFailure(action: (E) -> Unit): Result<D, E> {
    if (this is Result.Failure) action(error)
    return this
}

/** Discards the success value, keeping the failure side (for `Unit`-typed blocks). */
public fun <D, E : MpfrError> Result<D, E>.discard(): Result<Unit, E> =
    when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> this
    }

/** Result of side-effecting computations that can only fail. */
public typealias EmptyResult<E> = Result<Unit, E>
