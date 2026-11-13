import app.cash.licensee.LicenseeTask
import io.github.munetoshi.slidecompose.buildlogic.CheckNotices
import io.github.munetoshi.slidecompose.buildlogic.Component
import io.github.munetoshi.slidecompose.buildlogic.CreateSigningKeychain
import io.github.munetoshi.slidecompose.buildlogic.DownloadFile
import io.github.munetoshi.slidecompose.buildlogic.GenerateNotices
import io.github.munetoshi.slidecompose.buildlogic.JavaRuntimeNotice
import io.github.munetoshi.slidecompose.buildlogic.UpdateNotices
import io.github.munetoshi.slidecompose.buildlogic.jdkSourcesUrl
import io.github.munetoshi.slidecompose.buildlogic.localSigner
import io.github.munetoshi.slidecompose.buildlogic.parseRelease
import io.github.munetoshi.slidecompose.buildlogic.signApp
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    dependencies {
        constraints {
            // Licensee reads POMs with Maven's model builder, which brings plexus-utils 3.6.0:
            // CVE-2025-67030, directory traversal in extractFile. Remove once Licensee brings 3.6.1 or later.
            classpath("org.codehaus.plexus:plexus-utils:3.6.2")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.licensee)
}

/** The version in gradle.properties, which every module has. */
val appVersion = version.toString()

// jpackage refuses versions starting with 0 on macOS, though macOS takes them. So jpackage gets
// one it takes, and the app's Info.plist and the DMG's name get the real one afterwards: the DMG is
// made from the app that createDistributable made, which signs it again after the change.
val jpackageVersion = if (appVersion.startsWith("0.")) "1.0" else appVersion

/** SlideCompose is licensed under the Apache License 2.0, in LICENSE. */
val appCopyright = "Copyright 2026 munetoshi"

/** The major version of the JDK that the app bundles and runs on. */
val bundledJdk = 25

/** The JDK that the app bundles and runs on, whichever JDK runs Gradle. */
val bundledJavaHome: Directory = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(bundledJdk)
    vendor = JvmVendorSpec.ADOPTIUM
}.get().metadata.installationPath

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
    implementation(project(":slidecompose"))
    implementation(compose.desktop.currentOs)
    // Compiles slide decks at runtime. The versions must match the Kotlin version the app is built with.
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.compose.compiler.plugin.embeddable)
    implementation(libs.compose.resources)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.compose.ui.test)
}

tasks.test {
    useJUnitPlatform()
    // Decks compile and render off-screen, without windows.
    systemProperty("java.awt.headless", "true")
    // For checking that the About window shows it.
    systemProperty("slidecompose.version", appVersion)
}

tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

// The app's own text, in English and Japanese (composeResources/values-ja).
compose.resources {
    packageOfResClass = "io.github.munetoshi.slidecompose.viewer.generated.resources"
}

// The licenses of what the app bundles. Licensee fails the build for a library under a license not
// allowed here; one that is allowed also needs its text in licenses/.
licensee {
    allow("Apache-2.0")
}

// THIRD_PARTY_NOTICES.md and the About window's list, from the libraries on the runtime classpath
// and the software from elsewhere listed here, with its text in licenses/.
val generateNotices = tasks.register<GenerateNotices>("generateNotices") {
    description = "Collects the licenses of what the app bundles."
    licenseeReport = tasks.named<LicenseeTask>("licensee").flatMap { it.jsonOutput }
    libraries(configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts })
    template = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
    licenseTexts = rootProject.layout.projectDirectory.dir("licenses")
    components.addAll(
        Component("Skia", libs.versions.skia.get(), "BSD-3-Clause", "Skia.txt"),
        // In Skiko's native library and in skpdf, with Skia.
        Component("HarfBuzz", "", "MIT-Modern-Variant", "HarfBuzz.txt"),
        Component("libjpeg-turbo", "", "IJG AND BSD-3-Clause", "libjpeg-turbo.txt"),
        Component("libpng", "", "libpng-2.0", "libpng.txt"),
        Component("libwebp", "", "BSD-3-Clause", "libwebp.txt"),
        Component("Wuffs", "", "Apache-2.0 OR MIT", "Wuffs.txt"),
        Component("zlib (Chromium)", "", "Zlib", "zlib.txt"),
        Component("skcms", "", "BSD-3-Clause", "Skia.txt"),
        // In Skiko's native library only (third_party/externals in Skia's DEPS at that version).
        Component("ICU", "74", "Unicode-3.0", "ICU.txt"),
        Component("Expat", "2.7.4", "MIT", "Expat.txt"),
        Component("DNG SDK", "", "LicenseRef-DNG-SDK", "DNG-SDK.txt"),
        Component("piex", "", "Apache-2.0", "piex.txt"),
        // Inside kotlin-compiler-embeddable, under its own packages. Versions from the Kotlin
        // build's gradle/libs.versions.toml where it names them.
        Component("IntelliJ Platform", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("Guava", "33.3.1-jre", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("fastutil", "8.5.14-jb1", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("kotlinx.collections.immutable", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("OpenTelemetry API", "1.41.0", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("Aalto XML", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("Jansi", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("LZ4 Java", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("StreamEx", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("javax.inject", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("Vavr", "0.10.4", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("Apache log4j", "", "Apache-2.0", "Kotlin-compiler-Apache.txt"),
        Component("ANTLR 4 runtime", "4.13.2", "BSD-3-Clause", "ANTLR.txt"),
        Component("JLine", "3.30.16", "BSD-3-Clause", "JLine.txt"),
        Component("Protocol Buffers", "4.28.2", "BSD-3-Clause", "protobuf.txt"),
        Component("ASM", "9.6.1", "BSD-3-Clause", "ASM.txt"),
        Component("Stax2 API", "", "BSD-2-Clause", "Stax2.txt"),
        Component("JDOM", "", "LicenseRef-JDOM", "JDOM.txt"),
        Component("PicoContainer", "", "BSD-3-Clause", "PicoContainer.txt"),
        // In LZ4 Java's native libraries for Linux and Windows.
        Component("LZ4, xxHash", "", "BSD-2-Clause", "LZ4.txt"),
        // Its exact version and source, as the app shows them, depend on the JDK that builds it.
        Component("Eclipse Temurin (OpenJDK)", "$bundledJdk", "GPL-2.0-only WITH Classpath-exception-2.0", "Temurin.txt", listOf("java-runtime")),
    )
    notices = layout.buildDirectory.file("notices/THIRD_PARTY_NOTICES.md")
    aboutResources = layout.buildDirectory.dir("generated/about")
    aboutPath = "io/github/munetoshi/slidecompose/viewer/notices"
}

tasks.register<UpdateNotices>("updateNotices") {
    description = "Writes the collected licenses to THIRD_PARTY_NOTICES.md."
    generated = generateNotices.flatMap { it.notices }
    target = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
}

val checkNotices = tasks.register<CheckNotices>("checkNotices") {
    group = "verification"
    description = "Checks that THIRD_PARTY_NOTICES.md lists what the app bundles."
    generated = generateNotices.flatMap { it.notices }
    committed = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
    result = layout.buildDirectory.file("notices/check.txt")
}
tasks.named("check") { dependsOn(checkNotices) }

// The bundled JDK's version, and where its complete source code is, which GPL version 2 asks
// whoever distributes it to offer: in the app and its About window.
val javaRuntimeNotice = tasks.register<JavaRuntimeNotice>("javaRuntimeNotice") {
    release = bundledJavaHome.file("release")
    notice = layout.buildDirectory.file("notices/JAVA_RUNTIME.txt")
}

// That source, to publish next to the app, checked against the SHA-256 that Adoptium's API gives for it.
tasks.register<DownloadFile>("jdkSources") {
    group = "distribution"
    description = "Downloads the source code of the JDK that the app bundles, to publish with the app."
    val release = providers.fileContents(bundledJavaHome.file("release")).asText.map { parseRelease(it) }
    url = release.map { jdkSourcesUrl(it) }
    sha256 = release.map { io.github.munetoshi.slidecompose.buildlogic.jdkSourcesChecksum(it) }
    file = layout.buildDirectory.file(url.map { "jdk-sources/${it.substringAfterLast('/')}" })
}

val aboutProperties = tasks.register<WriteProperties>("aboutProperties") {
    destinationFile = layout.buildDirectory.file("tmp/aboutProperties/about.properties")
    property("version", appVersion)
    property("copyright", appCopyright)
}

// What the About window shows besides the notices: the icon, the version and copyright, and the bundled JDK.
val aboutApp = tasks.register<Sync>("aboutApp") {
    from(aboutProperties)
    from("icons/icon.svg")
    from(javaRuntimeNotice.flatMap { it.notice }) {
        into("notices/texts")
        rename { "java-runtime.txt" }
    }
    into(layout.buildDirectory.dir("generated/aboutApp/io/github/munetoshi/slidecompose/viewer"))
}

sourceSets.main {
    resources.srcDir(generateNotices.flatMap { it.aboutResources })
    resources.srcDir(files(layout.buildDirectory.dir("generated/aboutApp")).builtBy(aboutApp))
}

// The licenses go in the app too, under Contents/app/resources.
val notices = tasks.register<Sync>("syncNotices") {
    from(rootProject.file("LICENSE"))
    from(generateNotices.flatMap { it.notices })
    from(javaRuntimeNotice.flatMap { it.notice })
    into(layout.buildDirectory.dir("appResources/common"))
}

// Registered by the Compose plugin once the application is configured.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(notices) }

compose.desktop.application {
    mainClass = "io.github.munetoshi.slidecompose.viewer.MainKt"
    javaHome = bundledJavaHome.asFile.absolutePath
    // Skiko loads its native library, and the Kotlin compiler frees file buffers through
    // sun.misc.Unsafe. Both are fine, but newer JDKs warn about them unless allowed.
    jvmArgs("-Xmx2g", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        appResourcesRootDir = layout.buildDirectory.dir("appResources")
        packageName = "SlideCompose"
        packageVersion = jpackageVersion
        copyright = appCopyright
        description = "Presents slide decks written in Jetpack Compose"
        // The embedded Kotlin compiler needs JDK modules (and JDK class files to compile against), so
        // the app gets them all but jlink and jpackage, which needs it: a JDK without packaged
        // modules, such as Temurin 25, can copy itself but not jlink.
        modules.addAll(jdkModules(javaHome).filterNot { it == "jdk.jlink" || it == "jdk.jpackage" })
        macOS {
            bundleID = "io.github.munetoshi.slidecompose.viewer"
            // The logo on a rounded square, from icons/icon.svg (docs/logo.svg on the macOS icon grid,
            // as large in the square as the system's own icons): rendered by Quick Look, which keeps
            // its clip paths, with the outside of the square made transparent, then put together
            // at the sizes of an iconset with iconutil.
            iconFile.set(project.file("icons/SlideCompose.icns"))
            // Lets Finder's "Open With" and drag & drop onto the app hand over .kt files and folders,
            // without making SlideCompose the default app for them. The localizations let macOS
            // offer a language per app, and translate its own parts, such as the file dialogs.
            infoPlist {
                extraKeysRawXml = """
                    <key>CFBundleLocalizations</key>
                    <array>
                      <string>en</string>
                      <string>ja</string>
                    </array>
                    <key>CFBundleDocumentTypes</key>
                    <array>
                      <dict>
                        <key>CFBundleTypeName</key>
                        <string>Slide Deck</string>
                        <key>CFBundleTypeRole</key>
                        <string>Viewer</string>
                        <key>LSHandlerRank</key>
                        <string>Alternate</string>
                        <key>LSItemContentTypes</key>
                        <array>
                          <string>public.source-code</string>
                          <string>public.plain-text</string>
                          <string>public.data</string>
                          <string>public.folder</string>
                        </array>
                      </dict>
                    </array>
                """.trimIndent()
            }
        }
    }
}

// The JVM launcher that safe mode's child process needs: Compose strips bin/ from the bundled
// runtime, so it is copied back in below. Captured here so the doLast does not touch the project.
val bundledJavaLauncher = bundledJavaHome.file("bin/java").asFile

// The signing identity of builds on this Mac, made once by createSigningKeychain. macOS remembers
// what the user allowed the app, such as reading the Documents folder, by who signed it. Without
// this identity, each build is signed ad-hoc, and macOS takes it for a new app without them.
val signingKeychain = File(System.getProperty("user.home"), "Library/Keychains/slidecompose-signing.keychain-db")

tasks.register<CreateSigningKeychain>("createSigningKeychain") {
    group = "distribution"
    description = "Makes this Mac's signing identity for the app, so that the app keeps its permissions across builds."
    keychain = signingKeychain
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    // Builds the app again once the identity is made or deleted.
    inputs.property("signer", localSigner(signingKeychain).toString())
    doLast {
        val plist = outputs.files.asFileTree.matching { include("*.app/Contents/Info.plist") }.singleFile
        val version = Regex("(<key>(?:CFBundleShortVersionString|CFBundleVersion)</key>\\s*<string>)[^<]*")
        plist.writeText(version.replace(plist.readText()) { it.groupValues[1] + appVersion })
        // Safe mode launches a child JVM in a sandbox, which needs `bin/java` (it finds libjli in
        // ../lib). Compose drops bin/ when it builds the runtime, so put the launcher back.
        val runtimeBin = File(plist.parentFile.parentFile, "Contents/runtime/Contents/Home/bin")
        runtimeBin.mkdirs()
        bundledJavaLauncher.copyTo(File(runtimeBin, "java"), overwrite = true).setExecutable(true)
        // The changes above break the signature jpackage made, so sign the app again.
        signApp(plist.parentFile.parentFile, localSigner(signingKeychain))
    }
}

tasks.matching { it.name == "packageDmg" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("*-$jpackageVersion.dmg") }.forEach { dmg ->
            dmg.renameTo(File(dmg.parentFile, dmg.name.replace("-$jpackageVersion.dmg", "-$appVersion.dmg")))
        }
    }
}

/** The modules of the JDK at [javaHome], as listed in its `release` file. */
fun jdkModules(javaHome: String): Sequence<String> =
    File(javaHome, "release").useLines { lines -> lines.first { it.startsWith("MODULES=") } }
        .removePrefix("MODULES=")
        .trim('"')
        .splitToSequence(' ')

tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}
