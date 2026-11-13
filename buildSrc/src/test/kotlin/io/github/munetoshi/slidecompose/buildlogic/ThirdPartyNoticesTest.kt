package io.github.munetoshi.slidecompose.buildlogic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Apache License 2.0 as the repository has it, as jars carry it. */
private val apacheLicense = File("../licenses/Apache-2.0.txt").readText()

/** The Apache License 2.0 without its appendix, as AndroidX and Compose jars carry it. */
private val apacheTerms = apacheLicense.substringBefore("APPENDIX:")

private fun library(group: String, artifact: String, version: String, vararg texts: JarText) =
    BundledLibrary(group, artifact, version, listOf("Apache-2.0"), texts.toList())

class ThirdPartyNoticesTest : FunSpec({
    context("licenseTextBeyondApache") {
        test("leaves out the Apache License with or without its appendix") {
            licenseTextBeyondApache(apacheLicense) shouldBe null
            licenseTextBeyondApache(apacheTerms) shouldBe null
        }

        test("keeps what follows the Apache License") {
            val extra = "EXTERNAL COMPONENTS\n\n   Copyright (c) 2002-2007, www.pdfbox.org"
            licenseTextBeyondApache("$apacheLicense\n$extra\n") shouldBe extra
            licenseTextBeyondApache("$apacheTerms\n$extra\n") shouldStartWith "EXTERNAL COMPONENTS"
        }

        test("keeps any other license whole, for its copyright lines") {
            val mit = "Copyright 2023 The Wuffs Authors\n\nPermission is hereby granted, free of charge, ...\n"
            licenseTextBeyondApache(mit) shouldBe mit.trim()
        }
    }

    test("isLicenseFile finds license and notice files at the root and under META-INF") {
        listOf("LICENSE", "NOTICE.txt", "META-INF/LICENSE", "META-INF/NOTICE.txt", "META-INF/androidx/collection/collection/LICENSE.txt", "META-INF/COPYING")
            .forEach { isLicenseFile(it) shouldBe true }
        listOf("org/example/License.class", "org/example/LICENSE", "META-INF/MANIFEST.MF", "META-INF/licensee.json")
            .forEach { isLicenseFile(it) shouldBe false }
    }

    test("textsInJar reads notices whole and licenses beyond the Apache License") {
        val jar = Files.createTempFile("library", ".jar").toFile().apply { deleteOnExit() }
        ZipOutputStream(jar.outputStream()).use { zip ->
            mapOf(
                "META-INF/LICENSE" to "$apacheLicense\nIncludes the Adobe Glyph List\n",
                "META-INF/NOTICE" to "Apache PDFBox\nCopyright 2002-2026 The Apache Software Foundation\n",
                "META-INF/androidx/x/LICENSE.txt" to apacheTerms,
                "org/apache/pdfbox/License.class" to "not a license",
            ).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        textsInJar(jar) shouldContainExactly listOf(
            JarText(JarText.Kind.License, "Includes the Adobe Glyph List"),
            JarText(JarText.Kind.Notice, "Apache PDFBox\nCopyright 2002-2026 The Apache Software Foundation"),
        )
    }

    test("withoutPlatform drops the platforms jars are named after") {
        withoutPlatform("ui-desktop") shouldBe "ui"
        withoutPlatform("annotation-jvm") shouldBe "annotation"
        withoutPlatform("skiko-awt-runtime-macos-arm64") shouldBe "skiko-awt-runtime"
        withoutPlatform("skiko-awt-runtime-linux-x64") shouldBe "skiko-awt-runtime"
        withoutPlatform("desktop-jvm-macos-x64") shouldBe "desktop"
        withoutPlatform("kotlin-stdlib") shouldBe "kotlin-stdlib"
    }

    test("licensesInReport reads SPDX ids, and the names of licenses without one") {
        val report = """
            [
              {"groupId": "org.example", "artifactId": "a", "version": "1.0", "spdxLicenses": [{"identifier": "Apache-2.0", "name": "Apache License 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0"}], "unknownLicenses": []},
              {"groupId": "org.example", "artifactId": "b", "version": "2.0", "unknownLicenses": [{"name": "Example License", "url": "https://example.org/license"}]}
            ]
        """.trimIndent()
        licensesInReport(report) shouldBe mapOf(
            "org.example:a:1.0" to listOf("Apache-2.0"),
            "org.example:b:2.0" to listOf("Example License"),
        )
    }

    context("libraryRows") {
        test("puts the artifacts of a group in one version on one row, without their platforms") {
            val rows = libraryRows(
                listOf(
                    library("org.jetbrains.compose.ui", "ui-text-desktop", "1.12.2"),
                    library("org.jetbrains.compose.ui", "ui-desktop", "1.12.2"),
                    library("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
                    library("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                    library("org.jetbrains.skiko", "skiko-awt-runtime-macos-arm64", "0.150.2"),
                    library("org.jetbrains.skiko", "skiko-awt", "0.150.2"),
                ),
            )
            rows shouldContainExactly listOf(
                LibraryRow("org.jetbrains.compose.ui", listOf("ui", "ui-text"), "1.12.2", listOf("Apache-2.0")),
                LibraryRow("org.jetbrains.kotlin", listOf("kotlin-reflect"), "1.6.10", listOf("Apache-2.0")),
                LibraryRow("org.jetbrains.kotlin", listOf("kotlin-stdlib"), "2.4.21", listOf("Apache-2.0")),
                LibraryRow("org.jetbrains.skiko", listOf("skiko-awt", "skiko-awt-runtime"), "0.150.2", listOf("Apache-2.0")),
            )
        }

        test("keeps artifacts under different licenses apart") {
            val rows = libraryRows(listOf(library("org.example", "a", "1.0"), library("org.example", "b", "1.0").copy(licenses = listOf("MIT"))))
            rows.map { it.artifacts } shouldContainExactly listOf(listOf("a"), listOf("b"))
        }
    }

    test("libraryTexts lists each text once, with every module that has it") {
        val notice = JarText(JarText.Kind.Notice, "Apache PDFBox")
        val texts = libraryTexts(
            listOf(
                library("org.apache.pdfbox", "pdfbox", "3.0.8", notice, JarText(JarText.Kind.License, "EXTERNAL COMPONENTS")),
                library("org.apache.pdfbox", "fontbox", "3.0.8", notice),
            ),
        )
        texts shouldContainExactly listOf(
            LibraryText(JarText.Kind.Notice, "Apache PDFBox", listOf("org.apache.pdfbox:fontbox", "org.apache.pdfbox:pdfbox")),
            LibraryText(JarText.Kind.License, "EXTERNAL COMPONENTS", listOf("org.apache.pdfbox:pdfbox")),
        )
    }

    test("licenseTextsFor fails for a license without a text, naming the file to add") {
        val directory = Files.createTempDirectory("licenses").toFile().apply { deleteOnExit() }
        File(directory, "Apache-2.0.txt").writeText("Apache License\n")
        val rows = listOf(LibraryRow("org.example", listOf("a"), "1.0", listOf("Apache-2.0")))
        licenseTextsFor(rows, directory) shouldBe mapOf("Apache-2.0" to "Apache License")
        val error = shouldThrow<IllegalStateException> { licenseTextsFor(rows + rows.first().copy(licenses = listOf("MIT")), directory) }
        error.message shouldContain "licenses/MIT.txt"
    }

    context("replaceRegions") {
        val document = "# Notices\n\n<!-- generated:table -->\nold\n<!-- /generated:table -->\n\nText\n"

        test("replaces what is between the markers and keeps the rest") {
            val replaced = replaceRegions(document, mapOf("table" to "| a |"))
            replaced shouldBe "# Notices\n\n<!-- generated:table -->\n| a |\n<!-- /generated:table -->\n\nText\n"
            replaceRegions(replaced, mapOf("table" to "| a |")) shouldBe replaced
        }

        test("fails when a region is missing or unknown") {
            shouldThrow<IllegalStateException> { replaceRegions(document, mapOf("table" to "", "texts" to "")) }
            shouldThrow<IllegalStateException> { replaceRegions(document, emptyMap()) }
        }
    }

    test("codeBlock fences a text with more backticks than it has in a row") {
        codeBlock("plain") shouldBe "```text\nplain\n```"
        codeBlock("a ``` b") shouldBe "````text\na ``` b\n````"
    }

    test("licenseTextsSection lists licenses, jar texts and components, each text once") {
        val section = licenseTextsSection(
            mapOf("Apache-2.0" to "Apache License"),
            listOf(LibraryText(JarText.Kind.Notice, "Apache PDFBox", listOf("org.apache.pdfbox:pdfbox"))),
            listOf(Component("Skia", "m150", "BSD-3-Clause", "Skia.txt"), Component("skcms", "", "BSD-3-Clause", "Skia.txt")),
            mapOf("Skia.txt" to "Copyright (c) 2011 Google Inc."),
        )
        section shouldBe """
            ### Apache-2.0

            ```text
            Apache License
            ```

            ### org.apache.pdfbox:pdfbox — NOTICE

            ```text
            Apache PDFBox
            ```

            ### Skia, skcms

            ```text
            Copyright (c) 2011 Google Inc.
            ```
        """.trimIndent()
    }

    test("aboutEntries gives each row its licenses and the texts of its jars, and components the app's texts too") {
        val rows = listOf(
            LibraryRow("org.apache.pdfbox", listOf("fontbox", "pdfbox"), "3.0.8", listOf("Apache-2.0")),
            LibraryRow("org.jetbrains.kotlin", listOf("kotlin-stdlib"), "2.4.21", listOf("Apache-2.0")),
        )
        val texts = listOf(
            LibraryText(JarText.Kind.Notice, "Apache Commons Logging", listOf("commons-logging:commons-logging")),
            LibraryText(JarText.Kind.Notice, "Apache PDFBox", listOf("org.apache.pdfbox:pdfbox")),
        )
        val components = listOf(
            Component("Skia", "m150", "BSD-3-Clause", "Skia.txt"),
            Component("Eclipse Temurin", "25", "GPL-2.0-only WITH Classpath-exception-2.0", "Temurin.txt", listOf("java-runtime")),
        )
        val entries = aboutEntries(rows, texts, components)
        entries shouldContainExactly listOf(
            AboutEntry("org.apache.pdfbox", "fontbox, pdfbox", "3.0.8", "Apache-2.0", listOf("license-Apache-2.0", "library-1")),
            AboutEntry("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21", "Apache-2.0", listOf("license-Apache-2.0")),
            AboutEntry("Skia", "", "m150", "BSD-3-Clause", listOf("component-Skia")),
            AboutEntry("Eclipse Temurin", "", "25", "GPL-2.0-only WITH Classpath-exception-2.0", listOf("component-Temurin", "java-runtime")),
        )
        entries.first().toLine() shouldBe "org.apache.pdfbox\tfontbox, pdfbox\t3.0.8\tApache-2.0\tlicense-Apache-2.0 library-1"
    }
})
