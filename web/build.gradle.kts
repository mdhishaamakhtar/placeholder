import com.varabyte.kobweb.gradle.application.util.configAsKobwebApplication
import kotlinx.html.link

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kobweb.application)
}

group = "dev.placeholder.web"
version = "0.1.0"

kobweb {
    app {
        index {
            faviconPath.set("/favicon.svg")
            // Title, description, theme color, and link-preview tags are written per page by the server (AppShell).
            head.add {
                // The two faces every page uses first, fetched alongside the CSS instead of after it.
                link(rel = "preload", href = "/fonts/hanken-grotesk-400.woff2") {
                    attributes["as"] = "font"
                    attributes["type"] = "font/woff2"
                    attributes["crossorigin"] = ""
                }
                link(rel = "preload", href = "/fonts/chivo-mono-400.woff2") {
                    attributes["as"] = "font"
                    attributes["type"] = "font/woff2"
                    attributes["crossorigin"] = ""
                }
                link(rel = "stylesheet", href = "/styles.css")
            }
        }
    }
}

kotlin {
    configAsKobwebApplication("web")

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kobweb.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("highlight.js", libs.versions.highlightjs.get()))
        }
    }
}

// The deployable site: Kobweb's generated index.html and public files, plus the production bundle.
tasks.register<Sync>("assembleSite") {
    dependsOn("jsBrowserProductionWebpack", "jsProcessResources")
    from(layout.buildDirectory.dir("processedResources/js/main/public")) { exclude("_kobweb/**") }
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) { include("web.js") }
    into(layout.buildDirectory.dir("site"))
}
