plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kobweb.application) apply false
    // Kobweb brings KSP 2.3.11, which uses an API Gradle 11 removes. On the same classpath, the newer one wins.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.spotless)
}

// One formatter and linter for the whole repository: `./gradlew spotlessApply` fixes, `spotlessCheck` fails the
// build on anything unformatted. Kotlin goes through ktlint, configured in .editorconfig; other text files only get
// whitespace hygiene.
spotless {
    val ktlint = libs.versions.ktlint.get()
    // Named explicitly so Spotless treats it as an input: otherwise an .editorconfig change isn't re-checked.
    val editorConfig = rootProject.file(".editorconfig")

    // Only the repository's own files: everything git tracks, plus new files it hasn't been told to ignore. Build
    // output and local tool state (anything in .gitignore or .git/info/exclude) are never checked, so a clean
    // checkout and a working copy full of caches pass or fail alike.
    val repoFiles =
        providers
            .exec { commandLine("git", "ls-files", "--cached", "--others", "--exclude-standard") }
            .standardOutput.asText
            .get()
            .lines()
            .filter { it.isNotBlank() && file(it).isFile }

    fun withExtensions(vararg extensions: String) = files(repoFiles.filter { path -> extensions.any(path::endsWith) })

    kotlin {
        target(withExtensions(".kt"))
        ktlint(ktlint).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target(withExtensions(".gradle.kts"))
        ktlint(ktlint).setEditorConfigPath(editorConfig)
    }
    format("text") {
        target(withExtensions(".css", ".sql", ".yml", ".yaml", ".toml", ".json", ".sh", ".conf"))
        trimTrailingWhitespace()
        endWithNewline()
    }
    // Markdown keeps trailing spaces: two at the end of a line are a line break.
    format("markdown") {
        target(withExtensions(".md"))
        endWithNewline()
    }
}
