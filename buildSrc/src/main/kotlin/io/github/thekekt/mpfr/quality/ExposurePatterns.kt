package io.github.thekekt.mpfr.quality

/** Rule body — regex scanned against every included tracked-style text file.
 *  Self-match note: forbidden literals are assembled from concatenated string
 *  pieces so this file never trips its own rules. */
data class ExposureRule(val id: String, val regex: Regex)

/**
 * Single source of truth for the zero-internal-exposure gate:
 * tokens that must never appear in committed public artifacts
 * (sources, build files, generated pairs, root docs, workflows).
 * Exempt licenses/manual citations are handled per-file, not by
 * weakening these patterns.
 */
object ExposurePatterns {

    val rules: List<ExposureRule> = listOf(
        ExposureRule("mpfr-kmp-contract-id", Regex("""C4-0\d{2}""")),
        ExposureRule("amendment-id", Regex("""\bA-(?:0\d|1[0-3])\b""")),
        ExposureRule("adr-id", Regex("""\bAD""" + "R-0")),
        ExposureRule("decision-id", Regex("""\bD(?:7|8|9|10|11)\b""")),
        ExposureRule("internal-design-doc", Regex("kotlin" + "-design")),
        ExposureRule("internal-section-ref", Regex("""§\s*\d""")),
        ExposureRule("tracker-token-upper", Regex("""\bBACK""" + "LOG\\b")),
        ExposureRule("tracker-token-lower", Regex("""\bback""" + "log\\b")),
        ExposureRule("tracker-issue-id", Regex("MPFR" + "KMP-\\d")),
        ExposureRule("sidequest-id", Regex("""\bSQ[12]\b""")),
        ExposureRule("process-methodology", Regex("Gate-" + "Automate")),
        ExposureRule("private-research-path", Regex("""\bre""" + "search/")),
        ExposureRule("internal-contract-file", Regex("""\b\d{2,3}-[a-z][a-z-]*\.md\b""")),
        ExposureRule("ratification-vocabulary", Regex("""\bra""" + "tified\\b")),
        ExposureRule("dangling-build-doc", Regex("""native/jni/""" + "B" + "UILD\\.md")),
        ExposureRule("contract-invariant-ref", Regex("""\binvariant #\d""")),
    )

    /** Files (repo-relative) whose listed rule ids are legitimately matched
     *  (public license-section citations). */
    val exemptions: Map<String, Set<String>> = mapOf(
        "THIRD_PARTY_LICENSES.md" to setOf("internal-section-ref"),
    )

    val includeGlobs: List<String> = listOf(
        "*.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/libs.versions.toml",
        "README.md",
        "AGENTS.md",
        "LICENSE",
        "NOTICE",
        "THIRD_PARTY_LICENSES.md",
        "buildSrc/*.gradle.kts",
        "buildSrc/src/**",
        "library/*.gradle.kts",
        "library/api/**",
        "library/src/**",
        "library/native/jni/**",
        "examples/*.gradle.kts",
        "examples/src/**",
        "third_party/mpfr/*.md",
        "third_party/mpfr/*.sh",
        "third_party/mpfr/Dockerfile",
        "third_party/mpfr/docker-compose.yml",
        ".github/workflows/**",
    )

    val excludeGlobs: List<String> = listOf(
        "third_party/mpfr/src/**",
        "third_party/mpfr/gmp/**",
        "third_party/mpfr/build/**",
        "third_party/mpfr/install/**",
    )

    /** Directories pruned from the walk at any depth (never part of `includeGlobs`). */
    val skipDirs: Set<String> = setOf("build", ".gradle", ".kotlin", ".jj", ".git", ".idea")

    val binaryExtensions: Set<String> = setOf("xz", "jar", "so", "dll", "dylib", "o", "a", "class")
}
