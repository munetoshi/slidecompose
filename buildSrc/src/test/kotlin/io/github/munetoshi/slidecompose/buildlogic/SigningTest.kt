package io.github.munetoshi.slidecompose.buildlogic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

private val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * A bundle with [executable], a copy of [program], and an Info.plist naming it, like the app and
 * the Java runtime inside it that jpackage makes.
 */
private fun bundle(dir: File, identifier: String, executable: String, program: String) {
    File(dir, "Contents/MacOS").mkdirs()
    File(program).copyTo(File(dir, "Contents/MacOS/$executable")).setExecutable(true)
    File(dir, "Contents/Info.plist").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>CFBundleIdentifier</key>
          <string>$identifier</string>
          <key>CFBundleExecutable</key>
          <string>$executable</string>
          <key>CFBundleVersion</key>
          <string>1.0</string>
        </dict>
        </plist>
        """.trimIndent(),
    )
}

/**
 * An app as jpackage signs it, ad-hoc with the hardened runtime and an entitlement, with a Java
 * runtime bundle inside. [program] stands for the app's code, which changes from build to build.
 */
private fun builtApp(dir: File, program: String = "/usr/bin/true"): File {
    val app = File(dir, "Test.app")
    val runtime = File(app, "Contents/runtime")
    bundle(runtime, "io.github.munetoshi.slidecompose.test.runtime", "libjli", program)
    bundle(app, "io.github.munetoshi.slidecompose.test", "Test", program)
    val entitlements = File(dir, "entitlements.plist")
    entitlements.writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>com.apple.security.cs.allow-jit</key>
          <true/>
        </dict>
        </plist>
        """.trimIndent(),
    )
    execute("codesign", "--force", "--sign", "-", "--options", "runtime", runtime.path)
    execute("codesign", "--force", "--sign", "-", "--options", "runtime", "--entitlements", entitlements.path, app.path)
    return app
}

/** What the viewer's build does to the app after jpackage signed it, which breaks the signature. */
private fun changeAfterSigning(app: File) {
    val plist = File(app, "Contents/Info.plist")
    plist.writeText(plist.readText().replace("<string>1.0</string>", "<string>0.1</string>"))
    File(app, "Contents/runtime/Contents/Home/bin").mkdirs()
    File("/usr/bin/true").copyTo(File(app, "Contents/runtime/Contents/Home/bin/java")).setExecutable(true)
}

/** What codesign says of [app]'s signature. */
private fun signature(app: File): String = execute("codesign", "--display", "--verbose=2", "--entitlements", "-", app.path)

/**
 * The requirement that macOS checks a build against to tell whether it is the app it allowed. An
 * ad-hoc signature has none of its own, and codesign shows the one it implies after a `#`.
 */
private fun designatedRequirement(app: File): String =
    execute("codesign", "--display", "--requirements", "-", app.path).lineSequence()
        .first { "designated => " in it }
        .substringAfter("designated => ")

class SigningTest : FunSpec({
    val keychain = File(tempdir(), "signing.keychain-db")
    val identity by lazy { Signer.Identity(LOCAL_SIGNING_IDENTITY, keychain) }

    beforeSpec { if (isMac) createSigningKeychain(keychain, tempdir()) }
    afterSpec { if (keychain.exists()) execute("security", "delete-keychain", keychain.path) }

    test("builds on this Mac are signed by the keychain's identity once there is one, ad-hoc before") {
        val missing = File(tempdir(), "missing.keychain-db")
        localSigner(missing) shouldBe Signer.AdHoc
        val made = File(tempdir(), "made.keychain-db").apply { writeText("") }
        localSigner(made) shouldBe Signer.Identity(LOCAL_SIGNING_IDENTITY, made)
    }

    test("signing again keeps the identifier, the entitlements and the options, without a timestamp") {
        resigning(File("/a/Test.app"), identity) shouldBe listOf(
            "codesign", "--force", "--sign", LOCAL_SIGNING_IDENTITY, "--keychain", keychain.path,
            "--timestamp=none", "--preserve-metadata=identifier,entitlements,flags,runtime", "/a/Test.app",
        )
        resigning(File("/a/Test.app"), Signer.AdHoc).take(4) shouldBe listOf("codesign", "--force", "--sign", "-")
    }

    test("every build signed by this Mac's identity is the same app to macOS, unlike ad-hoc builds").config(enabled = isMac) {
        val first = builtApp(tempdir())
        val second = builtApp(tempdir(), program = "/usr/bin/false")
        signApp(first, identity)
        signApp(second, identity)
        designatedRequirement(first) shouldBe designatedRequirement(second)
        designatedRequirement(first) shouldContain "certificate leaf = H\""
        designatedRequirement(first) shouldNotContain "cdhash"

        val firstAdHoc = builtApp(tempdir())
        val secondAdHoc = builtApp(tempdir(), program = "/usr/bin/false")
        signApp(firstAdHoc, Signer.AdHoc)
        signApp(secondAdHoc, Signer.AdHoc)
        designatedRequirement(firstAdHoc) shouldContain "cdhash"
        designatedRequirement(firstAdHoc) shouldNotBe designatedRequirement(secondAdHoc)
    }

    test("signing the changed app again makes it valid, keeping the entitlements and the hardened runtime").config(enabled = isMac) {
        val app = builtApp(tempdir())
        changeAfterSigning(app)
        shouldThrow<GradleException> { execute("codesign", "--verify", "--deep", "--strict", app.path) }

        signApp(app, identity)
        val signature = signature(app)
        signature shouldContain "Authority=$LOCAL_SIGNING_IDENTITY"
        signature shouldContain "Identifier=io.github.munetoshi.slidecompose.test\n"
        signature shouldContain "(runtime)"
        signature shouldContain "com.apple.security.cs.allow-jit"
        signature(File(app, "Contents/runtime")) shouldContain "Authority=$LOCAL_SIGNING_IDENTITY"
    }

    test("without the keychain, the changed app is signed ad-hoc again, and valid").config(enabled = isMac) {
        val app = builtApp(tempdir())
        changeAfterSigning(app)
        signApp(app, Signer.AdHoc)
        val signature = signature(app)
        signature shouldContain "Signature=adhoc"
        signature shouldContain "(adhoc,runtime)"
        signature shouldContain "com.apple.security.cs.allow-jit"
    }

    test("the keychain is not replaced, as a new identity would lose what macOS allowed").config(enabled = isMac) {
        val before = execute("security", "find-identity", "-p", "codesigning", keychain.path)
        before shouldContain "\"$LOCAL_SIGNING_IDENTITY\""
        shouldThrow<GradleException> { createSigningKeychain(keychain, tempdir()) }
        execute("security", "find-identity", "-p", "codesigning", keychain.path) shouldBe before
    }

    test("making the keychain leaves the key nowhere else, and the user's keychains as they were").config(enabled = isMac) {
        val other = File(tempdir(), "other.keychain-db")
        val workDir = tempdir()
        createSigningKeychain(other, workDir)
        try {
            workDir.listFiles()!!.toList().shouldBeEmpty()
            PosixFilePermissions.toString(Files.getPosixFilePermissions(other.toPath())) shouldBe "rw-------"
            execute("security", "list-keychains", "-d", "user") shouldNotContain other.name
        } finally {
            execute("security", "delete-keychain", other.path)
        }
    }
})
