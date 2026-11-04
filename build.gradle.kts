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
    // Not the project's own files: build output, and state that local tools (design checks, plans, the IDE) keep in
    // the working copy. All of it is git-ignored too, but Spotless finds files by pattern, not by asking git.
    val generated =
        listOf(
            "**/build/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            "**/.kobweb/**",
            "**/.impeccable/**",
            "**/.superpowers/**",
            "**/.idea/**",
        )

    kotlin {
        target("**/*.kt")
        targetExclude(generated)
        ktlint(ktlint).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(generated)
        ktlint(ktlint).setEditorConfigPath(editorConfig)
    }
    format("text") {
        target("**/*.css", "**/*.sql", "**/*.yml", "**/*.yaml", "**/*.toml", "**/*.json", "**/*.sh", "**/*.conf")
        targetExclude(generated + "**/kotlin-js-store/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
    // Markdown keeps trailing spaces: two at the end of a line are a line break.
    format("markdown") {
        target("**/*.md")
        targetExclude(generated)
        endWithNewline()
    }
}
