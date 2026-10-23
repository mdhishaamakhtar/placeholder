plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kobweb.application) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.spotless)
}

// One formatter and linter for the whole repository: `./gradlew spotlessApply` fixes, `spotlessCheck` fails the
// build on anything unformatted. Kotlin goes through ktlint, configured in .editorconfig; other text files only get
// whitespace hygiene.
spotless {
    val ktlint = libs.versions.ktlint.get()
    val generated = listOf("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.kobweb/**")

    kotlin {
        target("**/*.kt")
        targetExclude(generated)
        ktlint(ktlint)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(generated)
        ktlint(ktlint)
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
