// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.docs

import com.openbank.libs.util.BuildInfo
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Self-publishes a service's bundled documentation at `/q/openbank/docs`.
 *
 * Conceptually a "management endpoint" — same pattern as `/q/health`,
 * `/q/metrics`, `/q/openapi`: served under the well-known `/q/...` prefix,
 * no app-level auth (mgmt port is network-gated), version-locked with the
 * running JAR.
 *
 * Language handling:
 *   - `?lang=cs` query parameter is the primary signal
 *   - `Accept-Language: cs` header is honoured if no query param given
 *   - default is `en` (DocsCatalog.DEFAULT_LANG)
 *   - fallback chain when requested lang missing: language-agnostic file → en → cs → any
 *
 * Endpoints:
 *   GET /q/openbank/docs[?lang=cs]            → JSON index for that lang
 *   GET /q/openbank/docs/_meta                → catalogue metadata
 *   GET /q/openbank/docs/{slug}[?lang=cs]     → text/markdown for that slug+lang
 *
 * The contract version is published in the index payload (`schema:
 * "openbank.docs.v2"` — v2 because the items shape now carries `lang` and
 * `availableLanguages`).
 *
 * Security:
 *   - Slug regex `^[a-z0-9-]{1,60}$` blocks path traversal.
 *   - Lang regex `^[a-z]{2}$` blocks header-injection.
 *   - Classpath resource lookup cannot escape the JAR even if regex bypassed.
 *   - `@PermitAll` because this endpoint is meant for the network-gated mgmt port.
 */
@Path("/q/openbank/docs")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
class DocsResource @Inject constructor(
    private val catalog: DocsCatalog,

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "openbank-service")
    private val serviceName: String,

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0")
    private val serviceVersion: String,
) {

    @GET
    @PermitAll
    fun index(
        @QueryParam("lang") langParam: String?,
        @HeaderParam("Accept-Language") acceptLanguage: String?,
    ): IndexPayload {
        val lang = resolveLang(langParam, acceptLanguage)
        return IndexPayload(
            schema = SCHEMA_VERSION,
            service = serviceName,
            version = serviceVersion,
            buildTime = BuildInfo.buildTime,
            gitCommit = BuildInfo.gitCommit,
            available = !catalog.isEmpty(),
            requestedLang = lang,
            availableLanguages = catalog.availableLanguages(),
            // Well-known related endpoints published alongside docs. Values are
            // relative paths; the caller (typically admin-ui) prefixes them with
            // its proxy or the service's base URL. We list the endpoints the
            // OpenBank standard fleet exposes — services that lack a given
            // endpoint will simply 404 when the user clicks the chip; we don't
            // probe at index time because some services boot lazily.
            links = LINKS,
            items = catalog.index(lang),
        )
    }

    @GET
    @Path("/_meta")
    @PermitAll
    fun meta(): DocsCatalog.Meta = catalog.meta()

    @GET
    @Path("/{slug}")
    @Produces("text/markdown; charset=utf-8")
    @PermitAll
    fun document(
        @PathParam("slug") slug: String,
        @QueryParam("lang") langParam: String?,
        @HeaderParam("Accept-Language") acceptLanguage: String?,
        @HeaderParam("If-None-Match") ifNoneMatch: String?,
    ): Response {
        if (!SAFE_SLUG.matches(slug)) {
            throw WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(mapOf("error" to "invalid slug", "slug" to slug))
                    .build(),
            )
        }
        val lang = resolveLang(langParam, acceptLanguage)
        val doc = catalog.read(slug, lang) ?: throw NotFoundException(
            Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(
                    mapOf(
                        "error" to "doc not found",
                        "slug" to slug,
                        "lang" to lang,
                        "available" to catalog.index(lang).map { it.slug },
                    ),
                )
                .build(),
        )
        val tag = EntityTag(doc.etag)
        if (ifNoneMatch != null && ifNoneMatch.trim('"') == doc.etag) {
            return Response.notModified(tag).build()
        }
        return Response.ok(doc.content, "text/markdown; charset=utf-8")
            .tag(tag)
            .header("Content-Language", doc.lang.ifEmpty { "any" })
            .header("Cache-Control", "public, max-age=60")
            .header("X-Doc-Title", doc.title)
            .build()
    }

    private fun resolveLang(queryParam: String?, acceptLanguage: String?): String {
        // Query param wins outright.
        val raw = queryParam?.trim()
            ?: acceptLanguage?.substringBefore(',')?.substringBefore(';')?.substringBefore('-')?.trim()
            ?: DocsCatalog.DEFAULT_LANG
        val candidate = raw.lowercase()
        return if (SAFE_LANG.matches(candidate)) candidate else DocsCatalog.DEFAULT_LANG
    }

    /** Wire payload for the index endpoint. */
    data class IndexPayload(
        val schema: String,
        val service: String,
        val version: String,
        val buildTime: String,
        val gitCommit: String,
        val available: Boolean,
        val requestedLang: String,
        val availableLanguages: List<String>,
        /** Well-known related endpoints (relative paths). */
        val links: Map<String, String>,
        val items: List<DocsCatalog.Summary>,
    )

    private companion object {
        // Schema bumped to v3 because the index payload now carries `links`.
        // Older clients ignore unknown fields, so v2 readers continue to work.
        const val SCHEMA_VERSION = "openbank.docs.v3"
        val SAFE_SLUG = Regex("^[a-z0-9-]{1,60}$")
        val SAFE_LANG = Regex("^[a-z]{2}$")

        // Standard OpenBank service well-known endpoints, in the order we
        // want chips to render. Keep this list short — they all map to the
        // hard-coded /q/* prefix the libs ServiceInfoResource + Quarkus
        // observability extensions provide. Service-specific extras (e.g.
        // BPMN viewer, ER diagram) can be added per service later via a
        // pluggable extension point if the need arises.
        val LINKS = linkedMapOf(
            "openapi" to "/q/openapi",
            "swagger" to "/q/swagger-ui",
            "health" to "/q/health",
            "metrics" to "/q/metrics",
            "info" to "/api/v1/info",
            "docsMeta" to "/q/openbank/docs/_meta",
        )
    }
}
