import io.github.thekekt.mpfr.quality.ExposurePatterns

plugins {
    base
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
    klib.enabled = false
    // The FFI seam (Repr bytes, MpfrBridge, opcode tables) is plumbing, not API.
    ignoredPackages.add("io.github.thekekt.mpfr.internal")
    // The consumer harness compiles against the public surface but publishes nothing.
    ignoredProjects.add("examples")
}

// Zero-internal-exposure gate: committed public artifacts must be self-describing
// (no internal ids / unpublished document references). The pattern list lives in
// a single place — buildSrc ExposurePatterns.kt — and `check` enforces it.
val checkZeroExposure by tasks.registering(io.github.thekekt.mpfr.quality.ZeroExposureTask::class) {
    scanRoot = layout.projectDirectory
    includeGlobs = ExposurePatterns.includeGlobs
    excludeGlobs = ExposurePatterns.excludeGlobs
    skipDirs = ExposurePatterns.skipDirs.toList()
    rules = ExposurePatterns.rules.associate { it.id to it.regex.pattern }
    exemptFileRules = ExposurePatterns.exemptions.mapValues { it.value.toList() }
    binaryExtensions = ExposurePatterns.binaryExtensions.toList()
}

tasks.named("check") {
    dependsOn(checkZeroExposure)
}
