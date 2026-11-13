package io.github.munetoshi.slidecompose.buildlogic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/** The `release` file of Temurin 25.0.4.1+1 for Apple silicon, but its list of modules. */
private val temurinRelease = """
    IMPLEMENTOR="Eclipse Adoptium"
    IMPLEMENTOR_VERSION="Temurin-25.0.4.1+1"
    JAVA_RUNTIME_VERSION="25.0.4.1+1-LTS"
    JAVA_VERSION="25.0.4.1"
    JAVA_VERSION_DATE="2026-08-18"
    SOURCE=".:git:520406d87195"
    BUILD_SOURCE="git:e6ba7dec3d07654074559310376a3ae89da5f4ac"
    BUILD_SOURCE_REPO="https://github.com/adoptium/temurin-build.git"
    SOURCE_REPO="https://github.com/adoptium/jdk25u.git"
    SEMANTIC_VERSION="25.0.4.1+1"
""".trimIndent()

/** What Adoptium's API answered for the sources of Temurin 25.0.4.1+1, but some of its fields. */
private val adoptiumSources = """
    {
        "binaries": [
            {
                "architecture": "x64",
                "image_type": "sources",
                "os": "linux",
                "package": {
                    "checksum": "cb9e50d2eb3de72ffd28466d7c511b4039b4bc4bd8f0d276d1e4cf81e30fdf09",
                    "checksum_link": "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk-sources_25.0.4.1_1.tar.gz.sha256.txt",
                    "link": "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk-sources_25.0.4.1_1.tar.gz",
                    "name": "OpenJDK25U-jdk-sources_25.0.4.1_1.tar.gz",
                    "size": 120577922
                },
                "scm_ref": "jdk-25.0.4.1+1_adopt"
            }
        ],
        "release_name": "jdk-25.0.4.1+1",
        "release_type": "ga",
        "vendor": "eclipse"
    }
""".trimIndent()

class JavaRuntimeTest : FunSpec({
    test("parseRelease reads the keys and unquotes the values") {
        val release = parseRelease(temurinRelease)
        release["SOURCE"] shouldBe ".:git:520406d87195"
        release["SEMANTIC_VERSION"] shouldBe "25.0.4.1+1"
    }

    test("jdkSourcesUrl is Adoptium's source archive of that build") {
        jdkSourcesUrl(parseRelease(temurinRelease)) shouldBe
            "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk-sources_25.0.4.1_1.tar.gz"
    }

    test("jdkSourcesUrl fails for a JDK from anyone else") {
        shouldThrow<IllegalStateException> { jdkSourcesUrl(parseRelease(temurinRelease.replace("Eclipse Adoptium", "Oracle Corporation"))) }
    }

    test("jdkSourcesChecksumUrl asks Adoptium's API for the sources of that release") {
        jdkSourcesChecksumUrl(parseRelease(temurinRelease)) shouldBe
            "https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-25.0.4.1%2B1?image_type=sources"
    }

    test("jdkSourcesChecksumUrl fails for a JDK from anyone else") {
        shouldThrow<IllegalStateException> { jdkSourcesChecksumUrl(parseRelease(temurinRelease.replace("Eclipse Adoptium", "Oracle Corporation"))) }
    }

    test("parseJdkSourcesChecksum reads the sum of the archive at that URL") {
        parseJdkSourcesChecksum(adoptiumSources, jdkSourcesUrl(parseRelease(temurinRelease))) shouldBe
            "cb9e50d2eb3de72ffd28466d7c511b4039b4bc4bd8f0d276d1e4cf81e30fdf09"
    }

    test("parseJdkSourcesChecksum fails when the API does not list that archive") {
        val error = shouldThrow<IllegalStateException> {
            parseJdkSourcesChecksum(adoptiumSources, "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/other.tar.gz")
        }
        error.message shouldContain "does not list"
    }

    test("javaRuntimeNotice names the build, its license and its source") {
        val notice = javaRuntimeNotice(parseRelease(temurinRelease))
        notice shouldContain "Eclipse Temurin 25.0.4.1+1"
        notice shouldContain "GNU General Public License, version 2, with the Classpath Exception"
        notice shouldContain "OpenJDK25U-jdk-sources_25.0.4.1_1.tar.gz"
        notice shouldContain "https://github.com/adoptium/jdk25u at commit 520406d87195"
        notice shouldContain "https://github.com/adoptium/temurin-build at commit e6ba7dec3d07654074559310376a3ae89da5f4ac"
    }

    test("the repository's LICENSE is the Apache License text the notices use") {
        File("../LICENSE").readText() shouldBe File("../licenses/Apache-2.0.txt").readText()
    }
})
