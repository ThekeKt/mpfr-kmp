import io.github.thekekt.mpfr.shim.GenerateShimTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.thekekt"
version = "0.0.0"

kotlin {
    // No accidental public API in the published library surface.
    explicitApi()
    // The wrapper is expect/actual-heavy everywhere.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    android {
        namespace = "io.github.thekekt.mpfrkmp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    // v1 release matrix: JVM desktop + Android. Kotlin/Native targets land with
    // their cinterop actuals in v1.x — an expect without an honest actual must
    // not enter the build (no API stubs).
    // iosX64()/iosArm64()/iosSimulatorArm64()/linuxX64() — v1.x.

    sourceSets {
        val jvmCommon by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmCommon)
        androidMain.get().dependsOn(jvmCommon)

        commonMain.dependencies {
            //put your multiplatform dependencies here
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The shim tables are the source of truth for both
// native/jni/mpfr_kmp_ops.h and internal/MpfrOps.kt (checked in; regenerate +
// commit together). No dedicated drift-check task is needed: the files are
// deterministic from the table — review relies on the ops-consistency jvmTest
// instead.
val generateShim by tasks.registering(GenerateShimTask::class) {
    opsHeaderFile = layout.projectDirectory.file("native/jni/mpfr_kmp_ops.h")
    kotlinOpsFile = layout.projectDirectory.file("src/commonMain/kotlin/io/github/thekekt/mpfr/internal/MpfrOps.kt")
}

afterEvaluate {
    // any Kotlin compile must see regenerated ops if the task is ever invoked in the same run
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        dependsOn(generateShim)
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "library", version.toString())

    pom {
        name = "MPFR for Kotlin Multiplatform"
        description = "Kotlin bindings for the MPFR scientific computation to be able to be used " +
                "on most of the common targets, including JVM, Android, Linux and wasmJs"
        inceptionYear = "2026"
        url = "https://github.com/thekekt/mpfr-kmp/"
        licenses {
            license {
                name = "XXX"
                url = "YYY"
                distribution = "ZZZ"
            }
        }
        developers {
            developer {
                id = "XXX"
                name = "YYY"
                url = "ZZZ"
            }
        }
        scm {
            url = "XXX"
            connection = "YYY"
            developerConnection = "ZZZ"
        }
    }
}
