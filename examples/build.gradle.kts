import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Consumer harness: depends on :library the way a real downstream KMP project
// would (public API only, commonMain implementation dependency). Not published;
// excluded from the binary-compatibility gate via the root `apiValidation` config.

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    android {
        namespace = "io.github.thekekt.mpfr.examples"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":library"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
