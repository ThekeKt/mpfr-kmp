plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    // ADR-005: public-binary-API gate; applied at the root, configures sub-projects.
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // ADR-005 surface covers the published matrix, not JVM only:
    // gate the Kotlin/Native klib ABI alongside the JVM `.api` dump.
    klib.enabled = true
}
