plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    // Gate for the public binary API; applied at the root, configures sub-projects.
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // API gate for the v0.0.1 matrix (JVM + Android class files). The Kotlin/Native
    // klib ABI dump is enabled together with the native targets (v1.x cinterop layer);
    // with no klib-producing target it fails as of BCV 0.18 otherwise.
    klib.enabled = true
}
