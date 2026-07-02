// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.psd2.infrastructure.security.QsealVerifier
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
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
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
class QsealSignatureFilter(
    @ConfigProperty(name = "openbank.psd2.qseal.enforce", defaultValue = "false")
    private val enforce: Boolean,
) : ContainerRequestFilter {

    private val log = Logger.getLogger(QsealSignatureFilter::class.java)

    // CodeQL java/log-injection: path is a raw request URI segment, logged verbatim below.
    // Strip CR/LF so an attacker can't forge additional log lines (log forging, CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    override fun filter(ctx: ContainerRequestContext) {
        val path = ctx.uriInfo.path
        // Only the Berlin write surface carries a body to sign; reads rely on QWAC transport auth.
        val signed = ctx.method == "POST" && (path.startsWith("v1/payments") || path.startsWith("v1/consents"))
        if (!signed) return

        val body = ctx.entityStream.readBytes()
        ctx.entityStream = ByteArrayInputStream(body)

        val outcome = evaluate(ctx, body)
        if (outcome == Outcome.VALID) return

        if (enforce) {
            log.warnf("QSEAL %s on %s — rejecting (enforce)", outcome, path.sanitizeForLog())
            val err = mapOf(
                "tppMessages" to listOf(mapOf("category" to "ERROR", "code" to "SIGNATURE_INVALID")),
            )
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity(err).build())
        } else {
            log.debugf("QSEAL %s on %s — allowing (advisory)", outcome, path.sanitizeForLog())
        }
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
