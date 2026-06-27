// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.SchemaCatalogSource
import com.openbank.libs.analytics.SchemaCatalog
import com.openbank.libs.analytics.SchemaKey
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Apicurio-backed [SchemaCatalogSource] (ADR-0023, F7): the durable schema registry that replaces the
 * static config catalogue. The artifact id is the `eventType`; its registered versions are the
 * accepted `schemaVersion`s, so the governance gate quarantines anything the registry has not been
 * taught.
 *
 * It loads over the Apicurio Registry v2 REST API (`/apis/registry/v2/groups/{group}/artifacts` then
 * `.../{id}/versions`) using the JDK [HttpClient] — no new Maven dependency (the
 * `apicurio-registry-serdes-avro-serde` catalog alias is for Kafka SerDe, a different concern). Apicurio
 * is already provisioned in `openbank-infra` (service `schema-registry:8081`).
 *
 * Boot resilience: a registry that is unreachable at startup MUST NOT stop the service booting — it
 * returns an empty catalogue (gate open) and logs loudly, mirroring the "open by default" philosophy
 * of the config source. It is the `@Alternative @Priority(100)` binding behind the `@Default`
 * [ConfigSchemaCatalogSource], gated at build time by `openbank.analytics.schema.backend=apicurio`.
 * Response parsing is pure and unit-tested without a server.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.schema.backend", stringValue = "apicurio")
open class ApicurioSchemaCatalogSource : SchemaCatalogSource {

    @ConfigProperty(name = "openbank.analytics.schema.apicurio.url", defaultValue = "http://localhost:8081")
    lateinit var url: String

    @ConfigProperty(name = "openbank.analytics.schema.apicurio.group", defaultValue = "default")
    lateinit var group: String

    @Inject
    lateinit var mapper: ObjectMapper

    private val log = Logger.getLogger(ApicurioSchemaCatalogSource::class.java)
    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    override fun load(): SchemaCatalog = try {
        val artifactIds = parseArtifactIds(httpGet("/apis/registry/v2/groups/$group/artifacts?limit=500"))
        val keys = artifactIds.flatMap { id ->
            val versions = parseVersionNumbers(
                httpGet("/apis/registry/v2/groups/$group/artifacts/${URLEncoder.encode(id, StandardCharsets.UTF_8)}/versions?limit=500")
            )
            versions.map { SchemaKey(id, it) }
        }.toSet()
        log.infof("loaded schema catalog from Apicurio group=%s artifacts=%d keys=%d", group, artifactIds.size, keys.size)
        SchemaCatalog(keys)
    } catch (e: Exception) {
        log.errorf(e, "Apicurio schema registry unreachable at %s (group=%s) — gate OPEN until next boot", url, group)
        SchemaCatalog(emptySet())
    }

    /** `{ "artifacts": [ { "id": "..." }, ... ] }` → list of artifact ids. Pure / unit-testable. */
    internal fun parseArtifactIds(json: String): List<String> =
        mapper.readTree(json).path("artifacts").mapNotNull { it.path("id").asText(null)?.takeIf { id -> id.isNotEmpty() } }

    /** `{ "versions": [ { "version": "1" }, ... ] }` → numeric versions (non-numeric ignored). Pure. */
    internal fun parseVersionNumbers(json: String): List<Int> =
        mapper.readTree(json).path("versions").mapNotNull { it.path("version").asText(null)?.trim()?.toIntOrNull() }

    /** GETs an Apicurio path; throws on non-2xx. `open` so tests stub it without a server. */
    protected open fun httpGet(path: String): String {
        val request = HttpRequest.newBuilder(URI.create("${url.trimEnd('/')}$path"))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Apicurio request failed: GET $path -> HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
        return response.body()
    }
}
