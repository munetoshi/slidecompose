package io.github.munetoshi.slidecompose.viewer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The viewer's packages depend on each other in one direction: none depends, directly or through
 * others, on a package that depends on it. A package depends on another when one of its files
 * imports a name from it, unless the file names it only in KDoc links.
 */
class PackageDependenciesTest : FunSpec({
    test("no package depends on itself through others") {
        val dependencies = packageDependencies(File("src/main/kotlin"))
        dependencies.shouldNotBeEmpty()
        dependencies.inCycles() shouldBe emptyList()
    }

    test("files name the viewer's declarations through imports, which the test above reads") {
        qualifiedNames(File("src/main/kotlin"), QualifiedName) shouldBe emptyList()
    }
})

/** A name of the viewer written with its package. */
private val QualifiedName = Regex("""\bio\.github\.munetoshi\.slidecompose\.viewer\.[\w.]+""")

/** A [file] of package [from] that uses [names] from package [to]. */
private data class Dependency(val from: String, val to: String, val file: String, val names: List<String>)

/** The dependencies between the packages of the Kotlin files under [root]. */
private fun packageDependencies(root: File): List<Dependency> {
    val sources = kotlinSources(root)
    val packages = sources.mapNotNull { packageOf(it.second) }.toSet()
    return sources.flatMap { (file, text) ->
        val from = packageOf(text) ?: return@flatMap emptyList()
        val code = text.replace(ImportLine, "").withoutComments()
        val comments = text.comments()
        ImportLine.findAll(text)
            .map { it.destructured }
            .mapNotNull { (path, alias) ->
                val to = importedPackage(path).takeIf { it in packages && it != from } ?: return@mapNotNull null
                val name = alias.ifEmpty { path.substringAfterLast('.') }
                // Code uses operators through their signs, as `by` uses `getValue`: only KDoc links leave an import out.
                if (name == "*" || code.uses(name) || !comments.links(name)) to to name else null
            }
            .groupBy({ it.first }, { it.second })
            .map { (to, names) -> Dependency(from, to, file.name, names) }
    }
}

/** Where the Kotlin files under [root] write a [name][qualifiedName] with its package, outside imports, comments and strings. */
private fun qualifiedNames(root: File, qualifiedName: Regex): List<String> =
    kotlinSources(root).flatMap { (file, text) ->
        val code = text.replace(PackageLine, "").replace(ImportLine, "").withoutCommentsOrStrings()
        qualifiedName.findAll(code).map { "${file.name}: ${it.value}" }.toList()
    }

private fun kotlinSources(root: File): List<Pair<File, String>> =
    root.walk().filter { it.isFile && it.extension == "kt" }.map { it to it.readText() }.toList()

/** The dependencies on a package that depends back on the one they come from, directly or through others. */
private fun List<Dependency>.inCycles(): List<String> {
    val targets = groupBy({ it.from }, { it.to })
    fun reaches(from: String, to: String): Boolean {
        val seen = mutableSetOf(from)
        val pending = ArrayDeque(listOf(from))
        while (pending.isNotEmpty()) {
            for (next in targets[pending.removeFirst()].orEmpty()) {
                if (next == to) return true
                if (seen.add(next)) pending += next
            }
        }
        return false
    }
    return filter { reaches(it.to, it.from) }.map { "${it.from} -> ${it.to}: ${it.file} uses ${it.names.joinToString()}" }
}

/** The package an import of [path] takes its name from: for a nested class, that of the outer one. */
private fun importedPackage(path: String): String =
    path.split('.').dropLast(1).dropLastWhile { it.first().isUpperCase() }.joinToString(".")

private fun packageOf(source: String): String? = PackageLine.find(source)?.groupValues?.get(1)

/** Whether this code names [name], as a whole word. */
private fun String.uses(name: String): Boolean = Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(this)

/** Whether these comments link to [name], as `[name]` and `[name.member]` do. */
private fun String.links(name: String): Boolean = Regex("""\[${Regex.escape(name)}[\].]""").containsMatchIn(this)

/** This code without its comments, KDoc among them. */
private fun String.withoutComments(): String = CommentOrString.replace(this) { if (it.value.isComment()) "" else it.value }

private fun String.withoutCommentsOrStrings(): String = CommentOrString.replace(this, "")

/** The comments in this code, KDoc among them. */
private fun String.comments(): String = CommentOrString.findAll(this).map { it.value }.filter { it.isComment() }.joinToString("\n")

private fun String.isComment(): Boolean = startsWith("/")

private val PackageLine = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
private val ImportLine = Regex("""^import\s+([\w.*]+)(?:\s+as\s+(\w+))?""", RegexOption.MULTILINE)

/** A block comment, a line comment, a raw string, a string or a character, whichever starts first. */
private val CommentOrString =
    Regex("""/\*.*?\*/|//[^\n]*|"{3}.*?"{3,}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])+'""", RegexOption.DOT_MATCHES_ALL)
