package ch.lauzhack.twin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The dependency rules of docs/CODE_ARCHITECTURE.md, checked on the sources (imports and fully qualified names).
 * A failure names the file and the dependency that breaks a rule: fix the code, or change the rule here and in the doc.
 */
class ArchitectureTest {
    private val root = File("src/main/kotlin/ch/lauzhack/twin")

    /** Lower layers: each may depend only on the listed packages (plus itself). */
    private val lower = mapOf(
        "model" to setOf(),
        "common" to setOf(),
        "state" to setOf("model", "common"),
        "db" to setOf("model", "common"),
        "twinapi" to setOf("common"),
        "query" to setOf("model", "common", "state", "db"),
        "report" to setOf("model", "common", "state", "db", "query"),
        "mqtt" to setOf("model", "common", "state", "db"),
    )

    /** Delivery / feature packages: leaves. They use the lower layers, and `auth` for `staffOnly { }`, never each other. */
    private val features = setOf("api.v1", "auth", "assistant", "passenger", "wallet", "push", "web")

    private class Source(val file: File, val pkg: String, val deps: Set<String>, val ktorServer: Boolean)

    private val sources: List<Source> by lazy {
        root.walkTopDown().filter { it.extension == "kt" }.map { f ->
            val text = f.readText()
            val pkg = Regex("^package ch\\.lauzhack\\.twin\\.?([\\w.]*)", RegexOption.MULTILINE).find(text)!!.groupValues[1]
            val refs = Regex("ch\\.lauzhack\\.twin\\.([a-z][\\w]*(?:\\.v1)?)\\.").findAll(text).map { it.groupValues[1] }
            Source(f, pkg, refs.filter { it != pkg }.toSet(), Regex("^import io\\.ktor\\.server\\.", RegexOption.MULTILINE).containsMatchIn(text))
        }.toList()
    }

    private fun check(violations: List<String>) =
        assertTrue(violations.isEmpty(), violations.joinToString("\n", prefix = "Architecture rule broken:\n"))

    @Test
    fun sourcesAreFound() = assertTrue(sources.size > 40, "expected the main sources under ${root.absolutePath}")

    @Test
    fun lowerLayersOnlyDependDownwards() = check(
        sources.filter { it.pkg in lower }.flatMap { s ->
            (s.deps - lower.getValue(s.pkg)).map { "${s.file.name} (${s.pkg}) depends on $it" }
        },
    )

    @Test
    fun noHttpServerBelowTheRoutes() = check(
        sources.filter { it.pkg in lower && it.ktorServer }.map { "${it.file.name} (${it.pkg}) imports io.ktor.server" },
    )

    @Test
    fun featuresDoNotDependOnEachOther() = check(
        sources.filter { it.pkg in features }.flatMap { s ->
            s.deps.filter { it in features && it != "auth" }.map { "${s.file.name} (${s.pkg}) depends on feature $it" }
        },
    )
}
