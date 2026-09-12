package io.github.thekekt.mpfr

/**
 * Marks declarations that exist only for this library's own machinery
 * (`Repr`, the bridge, opcode constants, raw-bit helpers). Everything annotated
 * is `internal` anyway; the marker documents intent for readers and reviewers,
 * not an enforcement gate.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPEALIAS, AnnotationTarget.PROPERTY_SETTER)
public annotation class InternalMpfrApi
