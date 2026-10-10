// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.adapter

import com.openbank.pension.application.port.out.EmployerDirectoryPort
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.application.port.out.TaxCertificateDocumentPort
import com.openbank.pension.domain.contribution.MandateKind
import com.openbank.pension.domain.incentive.TaxYearSummary
import io.quarkus.arc.profile.IfBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * STUB adapters for the S3 outbound ports (ADR-0334 S3). Each one is deterministic — the id it
 * returns is a name-based UUID of its idempotency input, so a retry yields the same id exactly as
 * the real, idempotent downstream must — and each says loudly in the log that it is a stub, so a
 * deployed stub is visible rather than quietly "succeeding" (the PushResult.skipped lesson, #4348).
 *
 * Follow-ups (tracked on #12350): pension-fund-service subscription API (S4), standing-order and
 * sdd mandate creation with a pension reference, kyb verified-business lookup by party id, and a
 * document-service tax-certificate template.
 */
private fun stubId(prefix: String, key: String): String =
    "$prefix-" + UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8))

/**
 * dev/test only (#12378): the real adapter is `PaymentMandateRestAdapter`
 * (standing-order-service / sdd-service), the only bean in a prod build.
 */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubPaymentMandateAdapter : PaymentMandatePort {
    private val log = Logger.getLogger(StubPaymentMandateAdapter::class.java)

    override suspend fun setUp(request: MandateRequest): String {
        log.warnf("STUB payment mandate: %s for contract %s not sent downstream", request.kind, request.contractId)
        return stubId("stub-mandate", "${request.contractId}:${request.kind}:${request.firstCollection}")
    }

    override suspend fun cancel(kind: MandateKind, externalId: String) {
        log.warnf("STUB payment mandate: cancel of %s %s not sent downstream", kind, externalId)
    }
}

/**
 * Accepts every party: there is no kyb lookup by party id yet. Fails OPEN, which is why it is a
 * stub and not an adapter — the real one must fail closed on an unverified employer.
 */
@ApplicationScoped
class StubEmployerDirectoryAdapter : EmployerDirectoryPort {
    private val log = Logger.getLogger(StubEmployerDirectoryAdapter::class.java)

    override suspend fun isVerifiedEmployer(employerPartyId: UUID): Boolean {
        log.warnf("STUB employer directory: employer %s accepted without a kyb check", employerPartyId)
        return true
    }
}

/** Only in `dev`/`test` builds; the real adapter is `DocumentServiceTaxCertificateAdapter` (#12379). */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubTaxCertificateDocumentAdapter : TaxCertificateDocumentPort {
    private val log = Logger.getLogger(StubTaxCertificateDocumentAdapter::class.java)

    override suspend fun generate(
        summary: TaxYearSummary,
        participantPartyId: UUID,
        contractReference: String,
    ): String {
        log.warnf(
            "STUB tax certificate: %d for contract %s not rendered by document-service",
            summary.taxYear,
            summary.contractId,
        )
        return stubId("stub-doc", "${summary.contractId}:${summary.taxYear}")
    }
}
