import io.github.munetoshi.slidecompose.buildlogic.DownloadZip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URLClassLoader

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

dependencies {
    api(compose.desktop.common)
    api(libs.compose.material3)
    implementation(libs.pdfbox)
    implementation(libs.compose.resources)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.compose.ui.test)
}

tasks.test {
    useJUnitPlatform()
    // Slides render off-screen, without windows.
    systemProperty("java.awt.headless", "true")
}

tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

// The presenter's and the windows' own text, in English and Japanese (composeResources/values-ja).
compose.resources {
    packageOfResClass = "io.github.munetoshi.slidecompose.generated.resources"
}

// Skiko comes with Compose, so a Compose update can move it to another Skia. This fails the build until
// `skia` in libs.versions.toml follows, as skpdf must be built against the Skia that Skiko is built on.
abstract class CheckSkiaVersion : DefaultTask() {
    @get:Classpath
    abstract val skiko: ConfigurableFileCollection

    @get:Input
    abstract val expected: Property<String>

    @TaskAction
    fun check() {
        val actual = URLClassLoader(skiko.map { it.toURI().toURL() }.toTypedArray(), null).use { loader ->
            val version = loader.loadClass("org.jetbrains.skiko.Version")
            version.getMethod("getSkia").invoke(version.getField("INSTANCE").get(null)) as String
        }
        if (actual != expected.get()) {
            throw GradleException("Skiko is built on Skia $actual, but libs.versions.toml has skia = \"${expected.get()}\": set it to \"$actual\", and skiaSha256 in slidecompose/build.gradle.kts to its archives' sums")
        }
    }
}

val skiaVersion = libs.versions.skia.get()
val checkSkiaVersion = tasks.register<CheckSkiaVersion>("checkSkiaVersion") {
    group = "verification"
    description = "Checks that skia in libs.versions.toml is the Skia that Skiko is built on."
    skiko.from(
        configurations.runtimeClasspath.get().incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier && it.group == "org.jetbrains.skiko" && it.module == "skiko-awt" }
        }.files,
    )
    expected = skiaVersion
}
tasks.named("check") { dependsOn(checkSkiaVersion) }

// skpdf (src/main/cpp/skpdf.cpp) makes vector PDFs with Skia's PDF backend, which Skiko leaves out.
// It is built against JetBrains' prebuilt Skia that Skiko is built on, so that it reads the pictures
// Skiko records, and goes into the jar as one macOS binary for both Apple silicon and Intel. On other
// systems it is not built, and PDF export makes pages of images.
val skpdfSource = file("src/main/cpp/skpdf.cpp")
val skpdfResources = layout.buildDirectory.dir("generated/skpdf")

// The SHA-256 of JetBrains' Skia archive for each architecture, of the Skia that `skia` in
// libs.versions.toml names. Skia is linked into skpdf, which goes into the jar, so a download that
// differs fails before anything is taken out of it. When checkSkiaVersion asks for another Skia,
// change these with it: GitHub gives each archive's digest, as
//   gh api repos/JetBrains/skia/releases/tags/<skia> --jq '.assets[] | [.name, .digest]'
// and downloadSkia-arm64 and downloadSkia-x64 then fail with the sum they got, to compare.
val skiaSha256 = mapOf(
    "arm64" to "76b4983295cf50cbc955ff111ac9211a5057fe1d13a8552431b8d559a5a19584",
    "x64" to "ad9d2f0a2567d9a2422fbc498d340de21082856f73815959a75974fe8ab7b3b7",
)

// Skia's names for the architectures, and clang's.
val skpdfArchitectures = mapOf("arm64" to "arm64", "x64" to "x86_64").map { (skia, clang) ->
    // Outside the build directory, so that a clean build does not download it again.
    val skiaDirectory = File(gradle.gradleUserHomeDir, "caches/slidecompose/skia/$skiaVersion/macos-$skia")
    val download = tasks.register<DownloadZip>("downloadSkia-$skia") {
        dependsOn(checkSkiaVersion)
        url = "https://github.com/JetBrains/skia/releases/download/$skiaVersion/Skia-$skiaVersion-macos-Release-$skia.zip"
        sha256 = skiaSha256.getValue(skia)
        include = listOf("include/**", "modules/skcms/**/*.h", "out/**/*.a")
        directory = skiaDirectory
    }
    tasks.register<Exec>("compileSkpdf-$skia") {
        val output = layout.buildDirectory.file("skpdf/$skia/skpdf").get().asFile
        val libraries = listOf("skia", "skia_ganesh_ext", "harfbuzz", "zlib", "png", "jpeg", "webp", "wuffs", "skcms")
            .map { "$skiaDirectory/out/Release-macos-$skia/lib$it.a" }
        inputs.file(skpdfSource)
        inputs.files(download)
        outputs.file(output)
        doFirst { output.parentFile.mkdirs() }
        commandLine(
            listOf("xcrun", "clang++", "-std=c++20", "-O2", "-arch", clang, "-mmacosx-version-min=11.0") +
                listOf("-I$skiaDirectory", "-DSK_RELEASE", "-DSKPDF_SKIA_VERSION=\"$skiaVersion\"") +
                listOf(skpdfSource.path, "-o", output.path) + libraries +
                listOf("-framework", "CoreText", "-framework", "CoreGraphics", "-framework", "CoreFoundation", "-framework", "ApplicationServices") +
                listOf("-Wl,-dead_strip", "-Wl,-S", "-Wl,-x"),
        )
    }
}

val skpdf = tasks.register<Exec>("skpdf") {
    val binaries = skpdfArchitectures.map { task -> task.map { it.outputs.files.singleFile } }
    val output = skpdfResources.get().file("io/github/munetoshi/slidecompose/skpdf/macos/skpdf").asFile
    inputs.files(binaries)
    outputs.file(output)
    doFirst { output.parentFile.mkdirs() }
    commandLine(listOf("lipo", "-create", "-output", output.path) + binaries.map { it.get().path })
}

if (System.getProperty("os.name").startsWith("Mac")) {
    sourceSets.main {
        resources.srcDir(files(skpdfResources).builtBy(skpdf))
    }
}
