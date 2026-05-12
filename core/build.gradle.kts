plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikariCp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.sqlite)
    implementation(libs.sqlite.jdbc)

    implementation(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.websockets)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}
