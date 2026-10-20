plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "dev.placeholder"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.micrometer.prometheus)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)

    implementation(libs.lettuce)
    implementation(libs.caffeine)
    implementation(libs.password4j)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.sql.formatter)
    implementation(libs.jsoup)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

tasks.test {
    useJUnitPlatform()
}

val webSite = project(":web").layout.buildDirectory.dir("site")

tasks.named<JavaExec>("run") {
    dependsOn(":web:assembleSite")
    environment("WEB_ROOT", webSite.get().asFile.absolutePath)
}

tasks.register<JavaExec>("renderPreviews") {
    description = "Renders sample link-preview cards; the site card is also the README header image."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.placeholder.server.og.RenderPreviewsKt")
    args(rootProject.layout.projectDirectory.dir(".github/assets").asFile.absolutePath)
}
