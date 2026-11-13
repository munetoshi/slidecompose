package io.github.munetoshi.slidecompose.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** The common name of the self-signed certificate that [createSigningKeychain] makes. */
const val LOCAL_SIGNING_IDENTITY = "SlideCompose Local Signing"

/**
 * Who signs the app. macOS remembers what the user allowed an app, such as reading the Documents
 * folder, by its signature: by who signed it, or for an ad-hoc signature by the exact code.
 */
sealed interface Signer {
    /** No one: macOS takes each build for a new app, which has none of the permissions. */
    data object AdHoc : Signer

    /** The identity named [name] in [keychain]: every build it signs is the same app to macOS. */
    data class Identity(val name: String, val keychain: File) : Signer
}

/** The signer of builds on this Mac: the identity in [keychain] if [createSigningKeychain] made it. */
fun localSigner(keychain: File): Signer =
    if (keychain.isFile) Signer.Identity(LOCAL_SIGNING_IDENTITY, keychain) else Signer.AdHoc

/**
 * codesign's arguments that sign [target] again by [signer], keeping what its old signature says
 * besides who made it: the identifier, the entitlements and options such as the hardened runtime.
 * Without a timestamp, which would need Apple's server, and which only distribution needs.
 */
fun resigning(target: File, signer: Signer): List<String> {
    val sign = when (signer) {
        Signer.AdHoc -> listOf("--sign", "-")
        is Signer.Identity -> listOf("--sign", signer.name, "--keychain", signer.keychain.path)
    }
    return listOf("codesign", "--force") + sign +
        listOf("--timestamp=none", "--preserve-metadata=identifier,entitlements,flags,runtime", target.path)
}

/**
 * Signs the app at [app] again by [signer], once the build has changed it: first its Java runtime,
 * a bundle inside, then the app. Fails unless both signatures are valid afterwards.
 */
fun signApp(app: File, signer: Signer) {
    if (signer is Signer.Identity) execute("security", "unlock-keychain", "-p", "", signer.keychain.path)
    execute(resigning(File(app, "Contents/runtime"), signer))
    execute(resigning(app, signer))
    execute("codesign", "--verify", "--deep", "--strict", app.path)
}

/**
 * Makes [keychain] with a new signing identity, [LOCAL_SIGNING_IDENTITY], for builds of the app on
 * this Mac, so that they keep what the user allowed the app. Refuses to replace a keychain that is
 * there, since a new identity would lose that. Its files on the way go in [workDir], and the key
 * leaves nothing behind there.
 *
 * The keychain has no password, so that builds sign without asking; only its owner may read it,
 * and it is not on the keychain search list. Its certificate is self-signed and trusted by nothing: macOS only checks
 * that each build was signed by the same one.
 */
fun createSigningKeychain(keychain: File, workDir: File) {
    if (keychain.exists()) {
        throw GradleException(
            "$keychain is there already. A new identity would lose what macOS allowed the app; " +
                "to start over anyway, delete it first with `security delete-keychain \"$keychain\"`.",
        )
    }
    workDir.mkdirs()
    try {
        addIdentity(keychain, newIdentity(workDir))
    } finally {
        workDir.listFiles()?.forEach { it.delete() }
    }
}

/** What OpenSSL's `req` makes: a certificate that can sign code and nothing else. */
private val certificateConfig = """
    [req]
    distinguished_name = name
    prompt = no
    x509_extensions = extensions
    [name]
    CN = $LOCAL_SIGNING_IDENTITY
    [extensions]
    basicConstraints = critical, CA:false
    keyUsage = critical, digitalSignature
    extendedKeyUsage = critical, codeSigning
""".trimIndent()

/** The PKCS#12 file's password: it only takes the key from OpenSSL to the keychain. */
private const val TRANSFER_PASSWORD = "slidecompose"

/**
 * A new key and self-signed certificate, valid for ten years, in a PKCS#12 file in [workDir]. By
 * macOS's own OpenSSL (LibreSSL), whose PKCS#12 `security import` reads, unlike OpenSSL 3's.
 */
private fun newIdentity(workDir: File): File {
    val config = File(workDir, "certificate.cnf").apply { writeText(certificateConfig) }
    val key = File(workDir, "key.pem")
    val certificate = File(workDir, "certificate.pem")
    val identity = File(workDir, "identity.p12")
    execute(
        "/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
        "-config", config.path, "-keyout", key.path, "-out", certificate.path,
    )
    execute(
        "/usr/bin/openssl", "pkcs12", "-export", "-inkey", key.path, "-in", certificate.path,
        "-out", identity.path, "-passout", "pass:$TRANSFER_PASSWORD",
    )
    return identity
}

/**
 * Makes [keychain] with the key and certificate in [identity], or none at all if that fails. It
 * never locks itself, and codesign may use the key without asking.
 */
private fun addIdentity(keychain: File, identity: File) {
    execute("security", "create-keychain", "-p", "", keychain.path)
    runCatching {
        Files.setPosixFilePermissions(keychain.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        execute("security", "set-keychain-settings", keychain.path)
        execute("security", "import", identity.path, "-k", keychain.path, "-P", TRANSFER_PASSWORD, "-T", "/usr/bin/codesign")
        execute("security", "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:", "-s", "-k", "", keychain.path)
    }.onFailure {
        execute("security", "delete-keychain", keychain.path)
    }.getOrThrow()
}

/** Runs [command] and returns what it printed; fails with that if it fails. */
internal fun execute(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) throw GradleException("Failed: ${command.joinToString(" ")}\n$output")
    return output
}

internal fun execute(vararg command: String): String = execute(command.toList())

/** Makes this Mac's signing keychain, by [createSigningKeychain]. */
@UntrackedTask(because = "It makes a keychain outside the build, once")
abstract class CreateSigningKeychain : DefaultTask() {
    @get:Internal
    abstract val keychain: RegularFileProperty

    @TaskAction
    fun create() {
        val file = keychain.get().asFile
        createSigningKeychain(file, temporaryDir)
        logger.lifecycle("Made $file. Builds of the app are now signed by \"$LOCAL_SIGNING_IDENTITY\".")
    }
}
