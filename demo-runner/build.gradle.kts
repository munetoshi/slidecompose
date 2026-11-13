import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// The demo deck is shared with the viewer app, which loads the same file at runtime.
sourceSets.main {
    kotlin.srcDir(rootProject.file("examples/demo"))
}

dependencies {
    implementation(project(":slidecompose"))
    implementation(compose.desktop.currentOs)
}

compose.desktop.application {
    mainClass = "MainKt"
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

// Renders the demo deck and some transition frames to build/snapshots, without opening a window.
tasks.register<JavaExec>("snapshots") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "SnapshotsKt"
    jvmArgs("-Djava.awt.headless=true")
    args(layout.buildDirectory.dir("snapshots").get().asFile.absolutePath)
}

tasks.register<JavaExec>("windowSmokeTest") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "WindowSmokeTestKt"
}
