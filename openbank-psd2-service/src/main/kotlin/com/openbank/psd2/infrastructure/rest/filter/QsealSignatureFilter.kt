// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.libs.security.sanitizeForLog
import com.openbank.psd2.infrastructure.security.QsealVerifier
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import java.io.ByteArrayInputStream

/**
 * eIDAS **QSEAL** message-signature gate for the Berlin `/v1` write surface (ADR-0090 P4).
 *
 * Verifies the Berlin `Digest` + `Signature` + `TPP-Signature-Certificate` triplet on signed
 * requests ([QsealVerifier]). Runs **after** [EidasMtlsFilter] (QWAC transport auth) — QSEAL adds
 * per-message integrity + non-repudiation on top of the transport identity.
 *
 * **Advisory by default** (`openbank.psd2.qseal.enforce=false`): a missing/invalid signature is
 * logged but the request proceeds — sandboxes have no real QSEAL chain. Flip to `true` per
 * environment to reject unsigned/forged requests (`SIGNATURE_INVALID`). Mirrors the OPA
 * advisory→enforce rollout (ADR-0034).
 *
 * **Runs on the IO thread by default (#11008-follow-up, issue TBD).** The guarded Berlin resource
 * methods are Kotlin `suspend fun`s (non-blocking to RESTEasy Reactive), and a `@ServerRequestFilter`
 * inherits the thread of the method it guards — it does not get a worker thread of its own.
 * [ContainerRequestContext.entityStream] can only be read with a genuinely BLOCKING call, so once
 * #10997 made this filter actually run on `v1/payments`/`v1/consents` POSTs, every one of them threw
 * `BlockingOperationNotAllowedException` ("Attempting a blocking read on io thread") before the
 * resource ever ran — measured via `PisIdempotencyFingerprintIT`'s Berlin path surfacing as a bare
 * 500/422. `@Blocking` on the `suspend` resource method is refused by Quarkus outright, and reading
 * the body off `@Context RoutingContext` NPEs this early in the filter chain (the Vert.x body
 * hasn't been bound to the routing context yet at JAX-RS filter time). The fix offloads the read —
 * and the signature verification that depends on the raw bytes — onto
 * [Infrastructure.getDefaultWorkerPool] via `Uni.createFrom().item { }.runSubscriptionOn(...)`
 * (same pattern as `CopilotChatResource.chatStream`), and returns `Uni<Response?>`: `null` lets the
 * filter chain proceed, a non-null item aborts with that response — mirroring the old
 * `ctx.abortWith(...)` calls exactly.
 */
@ApplicationScoped
class QsealSignatureFilter(
    @ConfigProperty(name = "openbank.psd2.qseal.enforce", defaultValue = "false")
    private val enforce: Boolean,
) {

    private val log = Logger.getLogger(QsealSignatureFilter::class.java)

    // Must run after EidasMtlsFilter (AUTHENTICATION): restores the @Priority the
    // ContainerRequestFilter carried before the conversion — a bare @ServerRequestFilter is USER.
    @ServerRequestFilter(priority = Priorities.AUTHORIZATION)
    fun filter(ctx: ContainerRequestContext): Uni<Response?> {
        // RESTEasy Reactive's UriInfo.path carries a leading slash ("/v1/..."); normalise once so the
        // prefix checks below match either form (#10997 — without this the gate never ran).
        val path = ctx.uriInfo.path.removePrefix("/")
        // Only the Berlin write surface carries a body to sign; reads rely on QWAC transport auth.
        val signed = ctx.method == "POST" && (path.startsWith("v1/payments") || path.startsWith("v1/consents"))
        if (!signed) return Uni.createFrom().nullItem()

        // entityStream.readBytes() is a genuinely blocking call — must not run on the IO thread.
        return Uni.createFrom().item<Response?> {
            val body = ctx.entityStream.readBytes()
            ctx.entityStream = ByteArrayInputStream(body)

            val outcome = evaluate(ctx, body)
            if (outcome == Outcome.VALID) {
                null
            } else if (enforce) {
                log.warnf("QSEAL %s on %s — rejecting (enforce)", outcome, path.sanitizeForLog())
                val err = mapOf(
                    "tppMessages" to listOf(mapOf("category" to "ERROR", "code" to "SIGNATURE_INVALID")),
                )
                Response.status(Response.Status.UNAUTHORIZED).entity(err).build()
            } else {
                log.debugf("QSEAL %s on %s — allowing (advisory)", outcome, path.sanitizeForLog())
                null
            }
        }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
    }

    private enum class Outcome { VALID, MISSING, BAD_DIGEST, BAD_SIGNATURE }

    private fun evaluate(ctx: ContainerRequestContext, body: ByteArray): Outcome {
        val sigHeader = ctx.getHeaderString("Signature")
        val certPem = ctx.getHeaderString("TPP-Signature-Certificate")
        val params = QsealVerifier.parseSignature(sigHeader) ?: return Outcome.MISSING
        val publicKey = QsealVerifier.publicKeyFromPem(certPem) ?: return Outcome.MISSING

        if (params.headers.contains("digest") && !QsealVerifier.digestMatches(body, ctx.getHeaderString("Digest"))) {
            return Outcome.BAD_DIGEST
        }
        val headerValues = params.headers.associateWith { ctx.getHeaderString(it).orEmpty() }
        val signingString = QsealVerifier.signingString(params, headerValues)
        val valid = QsealVerifier.signatureValid(signingString, params, publicKey)
        return if (valid) Outcome.VALID else Outcome.BAD_SIGNATURE
    }
}
