// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.libs.security.sanitizeForLog
import com.openbank.psd2.infrastructure.security.QsealVerifier
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
 * `readBody = true` makes RESTEasy Reactive buffer the request body (asynchronously) BEFORE this
 * filter runs, so `entityStream` is an in-memory stream and reading it is not a blocking read on
 * the Vert.x IO thread. A plain `ContainerRequestFilter` read the live stream on the IO thread ahead
 * of the `suspend` Berlin resources and failed every real `/v1` POST. The digest is still computed
 * over the exact wire bytes, never a re-serialised body.
 */
@ApplicationScoped
class QsealSignatureFilter(
    @ConfigProperty(name = "openbank.psd2.qseal.enforce", defaultValue = "false")
    private val enforce: Boolean,
) {

    private val log = Logger.getLogger(QsealSignatureFilter::class.java)

    // Moved to the shared com.openbank.libs.security.sanitizeForLog (#10907), imported above.

    /** `null` lets the request through, otherwise the response to abort with. */
    @ServerRequestFilter(priority = Priorities.AUTHORIZATION, readBody = true)
    fun filter(ctx: ContainerRequestContext): Response? {
        // RESTEasy Reactive's UriInfo.path carries a leading slash ("/v1/..."); normalise once so the
        // prefix checks below match either form (#10997 — without this the gate never ran).
        val path = ctx.uriInfo.path.removePrefix("/")
        // Only the Berlin write surface carries a body to sign; reads rely on QWAC transport auth.
        val signed = ctx.method == "POST" && (path.startsWith("v1/payments") || path.startsWith("v1/consents"))
        if (!signed) return null

        val body = ctx.entityStream.readBytes()
        ctx.entityStream = ByteArrayInputStream(body)

        val outcome = evaluate(ctx, body)
        if (outcome == Outcome.VALID) return null

        if (!enforce) {
            log.debugf("QSEAL %s on %s — allowing (advisory)", outcome, path.sanitizeForLog())
            return null
        }
        log.warnf("QSEAL %s on %s — rejecting (enforce)", outcome, path.sanitizeForLog())
        val err = mapOf(
            "tppMessages" to listOf(mapOf("category" to "ERROR", "code" to "SIGNATURE_INVALID")),
        )
        return Response.status(Response.Status.UNAUTHORIZED).entity(err).build()
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
