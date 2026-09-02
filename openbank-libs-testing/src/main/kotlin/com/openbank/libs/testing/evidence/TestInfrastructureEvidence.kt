// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.testing.evidence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Append-only, secret-free runtime evidence emitted by shared Testcontainers resources.
 * CI supplies [EVIDENCE_DIR]; local tests remain unaffected when it is absent.
 * Host names, mapped ports, credentials and container ids are deliberately never recorded.
 *
 * ## What a record MEANS (issue #7640) — read this before counting them
 *
 * A record is **one logical resource lifecycle**, not one physical container start. Quarkus
 * constructs a *fresh* `QuarkusTestResourceLifecycleManager` instance every time it
 * reprovisions test resources (`TestResourceManager.buildTestResourceEntry` calls
 * `testResourceClass.getConstructor().newInstance()`), so one JVM runs `start()` many times
 * over many instances whose cleanup can also invoke `stop()`. Emitting one record per `start()`
 * presented those as many *unterminated* resources — Product Catalog read 42 `started` against
 * 14 `stopped`. Because the reprovisioned instances are new objects, a per-instance guard cannot
 * see the repeat; the state has to outlive them, which is why the suppression lives in this object
 * and not in the ~14 emitters.
 *
 * So: a repeated `started` for the same (resource, image, opaque manager scope) is suppressed
 * until a `stopped` closes that lifecycle, and the number of suppressed reprovisions is published
 * on the terminal `stopped` record as `reprovisions` (absent when zero). Legacy emitters without
 * a scope retain the original (resource, image) grouping.
 *
 * ## What this deliberately STOPS observing, and what compensates
 *
 * 1. The individual timestamps of each physical provision leave this stream — only how many
 *    there were survives. A provisioning that **failed** and was retried therefore no longer
 *    appears here as its own `started`. That is the information cost of the fix, and it is
 *    deliberately not silent: the physical events remain independently observable from the
 *    Docker daemon event stream, which `collect-test-run-evidence.py` ingests into the same
 *    `observed` list from a separate `*.jsonl` file in this directory. Deduplicating here
 *    narrows one of two sources, never both.
 * 2. A lifecycle that never receives its `stopped` publishes no `reprovisions` count at all,
 *    because the count is only known once the lifecycle closes and this file is append-only.
 *    That is exactly the JVM-exit case (Ryuk reaps the containers) and is the residual blind
 *    spot of this design; the Docker stream above is what covers it.
 *
 * A `started` that is never followed by a `stopped` is still recorded and still visible as an
 * unterminated lifecycle: suppression only ever collapses a *repeat*, never the first one.
 */
object TestInfrastructureEvidence {
    private const val EVIDENCE_DIR = "OPENBANK_TEST_EVIDENCE_DIR"

    /** Test-only override for [EVIDENCE_DIR]; the environment variable wins when both are set. */
    private const val EVIDENCE_DIR_PROPERTY = "openbank.test.evidence.dir"

    /** (resource, image, opaque scope) -> physical reprovisions observed since that logical lifecycle opened. */
    private val openLifecycles = LinkedHashMap<String, Int>()

    /** Lifecycles already closed in this manager sequence; later physical stops are repeats. */
    private val closedLifecycles = mutableSetOf<String>()

    @Synchronized
    fun record(
        resource: String,
        image: String,
        lifecycle: String,
        resourceScopeId: String? = null,
        observedAt: Instant = Instant.now(),
    ) {
        require(lifecycle == "started" || lifecycle == "stopped") { "unsupported lifecycle" }
        require(resourceScopeId == null || UUID.fromString(resourceScopeId).toString() == resourceScopeId) {
            "resource scope id must be a canonical UUID"
        }
        val key = listOf(resource, image, resourceScopeId.orEmpty()).joinToString(" ")
        var reprovisions = 0
        if (lifecycle == "started") {
            val open = openLifecycles[key]
            if (open != null) {
                // Same logical resource, reprovisioned onto a new manager instance: count, don't emit.
                openLifecycles[key] = open + 1
                return
            }
            closedLifecycles.remove(key)
            openLifecycles[key] = 0
        } else {
            val open = openLifecycles.remove(key)
            if (open == null) {
                if (!closedLifecycles.add(key)) return
            } else {
                closedLifecycles.add(key)
                reprovisions = open
            }
        }
        write(resource, image, lifecycle, resourceScopeId, observedAt, reprovisions)
    }

    private fun write(
        resource: String,
        image: String,
        lifecycle: String,
        resourceScopeId: String?,
        observedAt: Instant,
        reprovisions: Int,
    ) {
        val directory = System.getenv(EVIDENCE_DIR)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(EVIDENCE_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: return
        val path = Path.of(directory).resolve("testcontainers.jsonl")
        Files.createDirectories(path.parent)
        val escapedResource = escape(resource)
        val escapedImage = escape(image)
        val scopeField = resourceScopeId?.let { ",\"resourceScopeId\":\"${escape(it)}\"" }.orEmpty()
        val reprovisionField = if (reprovisions > 0) ",\"reprovisions\":$reprovisions" else ""
        val line =
            """{"schemaVersion":1,"resource":"$escapedResource","image":"$escapedImage","lifecycle":"$lifecycle",""" +
                "\"observedAt\":\"$observedAt\"$scopeField$reprovisionField}\n"
        Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** Drops the in-JVM lifecycle state so a unit test can drive independent sequences. */
    @Synchronized
    internal fun resetForTesting() {
        openLifecycles.clear()
        closedLifecycles.clear()
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
