package io.github.thekekt.mpfr.quality

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.PathMatcher

/**
 * Zero-internal-exposure gate (single source of truth: [ExposurePatterns]).
 * Walks the included repo-relative globs under the project root and fails on
 * any forbidden token, honoring per-file exemptions (public license citations).
 */
abstract class ZeroExposureTask : DefaultTask() {

    /** Scanning the root tree is intentionally untracked: it would collide with
     *  every subproject's `build/` outputs as implicit task inputs. The rule list
     *  stays a real `@Input`, so config changes still break cache; freshness is
     *  guaranteed by always running. */
    @get:Internal
    abstract val scanRoot: DirectoryProperty

    @get:Input
    abstract val includeGlobs: ListProperty<String>

    @get:Input
    abstract val excludeGlobs: ListProperty<String>

    @get:Input
    abstract val skipDirs: ListProperty<String>

    @get:Input
    abstract val rules: MapProperty<String, String>

    @get:Input
    abstract val exemptFileRules: MapProperty<String, List<String>>

    @get:Input
    abstract val binaryExtensions: ListProperty<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = scanRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val includeMatchers = includeGlobs.get().map { matcher(it) }
        val excludeMatchers = excludeGlobs.get().map { matcher(it) }
        val skip = skipDirs.get().toSet()
        val binaries = binaryExtensions.get().map { ".$it" }.toSet()
        val compiled = rules.get().map { (id, regex) -> id to Regex(regex) }.toMap()
        val exemptions = exemptFileRules.get().mapValues { it.value.toSet() }

        val violations = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                val rel = root.relativize(path).toString().replace('\\', '/')
                if (skip.any { segment -> rel.split('/').contains(segment) }) return@forEach
                if (rel.substringAfterLast('.', "").let { ".$it" } in binaries) return@forEach
                val relPath = java.nio.file.Paths.get(rel)
                if (excludeMatchers.any { it.matches(relPath) }) return@forEach
                if (includeMatchers.none { it.matches(relPath) }) return@forEach
                val exemptForFile = exemptions[rel] ?: emptySet()
                val text = try {
                    Files.readString(path)
                } catch (e: Exception) {
                    return@forEach // not valid text; binary or undecodable
                }
                text.lineSequence().forEachIndexed { index, line ->
                    for ((id, regex) in compiled) {
                        if (id in exemptForFile) continue
                        if (regex.containsMatchIn(line)) {
                            violations += "$rel:${index + 1}: [$id] $line"
                        }
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "zero-internal-exposure check failed (${violations.size}): public artifacts must not " +
                    "reference internal ids/documents (see rules list in buildSrc ExposurePatterns.kt):\n" +
                    violations.joinToString("\n"),
            )
        }
        logger.lifecycle("checkZeroExposure: clean (${compiled.size} rules)")
    }

    private fun matcher(glob: String): PathMatcher =
        FileSystems.getDefault().getPathMatcher("glob:$glob")
}
