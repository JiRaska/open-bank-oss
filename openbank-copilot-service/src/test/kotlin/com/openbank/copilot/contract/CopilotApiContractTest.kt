// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.contract

import com.openbank.copilot.infrastructure.rest.ActionConfirmResource
import com.openbank.copilot.infrastructure.rest.CopilotChatResource
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method

/**
 * Bidirectional conformance between the published contract (`src/main/resources/openapi.yaml`) and
 * the JAX-RS routes this service declares.
 *
 * **What this proves, precisely.** It reads the `@Path` / `@POST` / `@GET` … annotations off the
 * resource classes by reflection and compares that route set with the `paths:` × HTTP-method set
 * parsed out of the spec, resolved against `servers[0].url` — an OpenAPI path is relative to the
 * server URL, so `/copilot/chat` under `servers: /api/v1` IS `/api/v1/copilot/chat`. Drift in
 * either direction fails.
 *
 * **What it does NOT prove.** Nothing here boots Quarkus or speaks HTTP: it does not exercise the
 * handlers, does not check status codes, request/response bodies or auth behaviour, and cannot
 * catch a route that exists as an annotation but fails at runtime (CDI, filters, policy gate).
 * Behaviour is covered elsewhere — `ActionConfirmResourceTest` for the confirm flow,
 * `CopilotServiceBootTest` for CDI wiring. This test's whole job is *route-set* drift, which is
 * exactly the defect it was written for: `/copilot/actions/{tokenId}/confirm` — the
 * human-in-the-loop confirmation for a money-path action proposal (ADR-0089 D2) — was served but
 * absent from the published spec entirely (#2255).
 */
class CopilotApiContractTest {

    private val specFile = File("src/main/resources/openapi.yaml")
    private val restSourceDir = File("src/main/kotlin/com/openbank/copilot/infrastructure/rest")

    /**
     * Every resource class this test reflects over. An explicit list goes stale silently, so
     * `every resource class is covered by this test` pins its size to the number of `*Resource.kt`
     * files on disk — a new resource file fails that guard until it is added here.
     */
    private val resourceClasses = listOf(CopilotChatResource::class.java, ActionConfirmResource::class.java)

    @Test
    fun `every route served is declared in the published spec`() {
        val served = servedRoutes()
        assertThat(served).describedAs("reflection found no JAX-RS route at all — the probe is broken").isNotEmpty()
        assertThat(served - declaredRoutes())
            .describedAs("served by a @Path resource but MISSING from openapi.yaml")
            .isEmpty()
    }

    @Test
    fun `every route declared in the published spec is served`() {
        val declared = declaredRoutes()
        assertThat(declared).describedAs("spec parse found no path at all — the probe is broken").isNotEmpty()
        assertThat(declared - servedRoutes())
            .describedAs("declared in openapi.yaml but NOT served by any @Path resource")
            .isEmpty()
    }

    @Test
    fun `the money-path confirm route is both served and declared`() {
        // Pinned by name, not only by set equality: this is the route whose absence started #2255,
        // and set equality alone would go green again if BOTH sides lost it.
        val confirm = "POST /api/v1/copilot/actions/{tokenId}/confirm"
        assertThat(servedRoutes()).contains(confirm)
        assertThat(declaredRoutes()).contains(confirm)
    }

    @Test
    fun `every resource class is covered by this test`() {
        val resourceFiles = restSourceDir.listFiles { f: File -> f.name.endsWith("Resource.kt") }.orEmpty()
        assertThat(resourceFiles.map { it.name }.sorted())
            .describedAs("a new *Resource.kt must be added to resourceClasses, or its routes go unchecked")
            .hasSameSizeAs(resourceClasses)
    }

    // ---------------------------------------------------------------- served side (reflection)

    private fun servedRoutes(): Set<String> = resourceClasses.flatMap { clazz ->
        val base = clazz.getAnnotation(Path::class.java)?.value.orEmpty()
        clazz.methods.mapNotNull { method ->
            httpVerbOf(method)?.let { verb -> "$verb ${join(base, method.getAnnotation(Path::class.java)?.value)}" }
        }
    }.toSet()

    private fun httpVerbOf(method: Method): String? =
        HTTP_METHOD_ANNOTATIONS.entries.firstOrNull { method.isAnnotationPresent(it.key) }?.value

    private fun join(base: String, sub: String?): String =
        ("/" + listOfNotNull(base, sub).joinToString("/") { it.trim('/') }).replace(MULTI_SLASH, "/").trimEnd('/')

    // ---------------------------------------------------------------- declared side (spec parse)

    /**
     * Minimal indentation walk of the `paths:` block — deliberately dependency-free, since no YAML
     * parser sits on this module's test classpath. Block scalars (`|`, `>`) are skipped wholesale so
     * a `description:` body can never be mistaken for a path or an operation.
     */
    private fun declaredRoutes(): Set<String> {
        val prefix = serverPrefix()
        val routes = mutableSetOf<String>()
        var currentPath: String? = null
        for ((indent, body) in pathsBlockLines()) {
            if (indent == PATH_INDENT) currentPath = pathKey(body)
            if (indent != OPERATION_INDENT) continue
            val verb = verbKey(body)
            val path = currentPath
            if (verb != null && path != null) routes += "$verb ${join(prefix, path)}"
        }
        return routes
    }

    /** Significant `(indent, text)` lines inside the top-level `paths:` block, block scalars dropped. */
    private fun pathsBlockLines(): List<Pair<Int, String>> {
        val significant = specFile.readLines()
            .map { line -> line.indexOfFirst { !it.isWhitespace() } to line.trim() }
            .filter { (indent, body) -> indent >= 0 && body.isNotEmpty() && !body.startsWith("#") }
        val pathsBlock = significant
            .dropWhile { (indent, body) -> !(indent == 0 && body == "paths:") }
            .drop(1)
            .takeWhile { (indent, _) -> indent > 0 }
        return withoutBlockScalars(pathsBlock)
    }

    private fun withoutBlockScalars(lines: List<Pair<Int, String>>): List<Pair<Int, String>> {
        var scalarAt = -1
        return lines.filter { (indent, body) ->
            val insideScalar = scalarAt >= 0 && indent > scalarAt
            val opensScalar = !insideScalar && BLOCK_SCALAR_TAIL.containsMatchIn(body)
            if (!insideScalar) scalarAt = if (opensScalar) indent else -1
            !insideScalar && !opensScalar
        }
    }

    private fun pathKey(body: String): String? = body.takeIf { it.startsWith("/") && it.endsWith(":") }?.dropLast(1)

    private fun verbKey(body: String): String? =
        body.dropLast(1).uppercase().takeIf { body.endsWith(":") && it in HTTP_VERBS }

    /** `servers[0].url` — the prefix every `paths:` key is relative to. */
    private fun serverPrefix(): String {
        val servers = specFile.readText().substringAfter("\nservers:", "").substringBefore("\npaths:")
        return SERVER_URL.find(servers)?.groupValues?.get(1)
            ?: error("openapi.yaml declares no servers[0].url — paths cannot be resolved")
    }

    private companion object {
        const val PATH_INDENT = 2
        const val OPERATION_INDENT = 4
        val MULTI_SLASH = Regex("/+")
        val BLOCK_SCALAR_TAIL = Regex("""[|>][-+]?$""")
        val SERVER_URL = Regex("""-\s+url:\s*"?([^"\s]+)"?""")

        val HTTP_METHOD_ANNOTATIONS: Map<Class<out Annotation>, String> = mapOf(
            POST::class.java to HttpMethod.POST,
            GET::class.java to HttpMethod.GET,
            PUT::class.java to HttpMethod.PUT,
            PATCH::class.java to HttpMethod.PATCH,
            DELETE::class.java to HttpMethod.DELETE,
        )
        val HTTP_VERBS: Set<String> = HTTP_METHOD_ANNOTATIONS.values.toSet()
    }
}
