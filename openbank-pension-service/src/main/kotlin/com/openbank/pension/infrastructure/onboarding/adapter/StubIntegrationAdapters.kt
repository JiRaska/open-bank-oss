// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.adapter

import com.openbank.pension.application.onboarding.CounterpartyDispatch
import com.openbank.pension.application.onboarding.CounterpartyReceipt
import com.openbank.pension.application.onboarding.GeneratedDocument
import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import com.openbank.pension.application.onboarding.KeyInformationDocumentPort
import com.openbank.pension.application.onboarding.KidRequest
import com.openbank.pension.application.onboarding.KycProfile
import com.openbank.pension.application.onboarding.KycStatus
import com.openbank.pension.application.onboarding.PartyKycPort
import com.openbank.pension.application.onboarding.PartyRelationPort
import com.openbank.pension.application.onboarding.SignatureOutcome
import com.openbank.pension.application.onboarding.SignatureVerificationPort
import com.openbank.pension.application.onboarding.TransferCounterpartyPort
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.domain.transfer.IncentiveHistoryEntry
import com.openbank.pension.domain.transfer.TransferRequest
import io.quarkus.arc.profile.IfBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.util.UUID

/**
 * STUB adapters for the collaborators slice S2 depends on (ADR-0334 S2 follow-ups, #12350).
 *
 * They FAIL CLOSED: unless `openbank.pension.stub-integrations.enabled=true` (set only in `%dev` and
 * `%test`), every call raises [IntegrationUnavailableException] (503). The SCA stub in particular
 * must never answer VERIFIED in a deployed environment — a signature check that did not happen is
 * not a pass (the `PushResult.skipped()` lesson, ADR-0252). When enabled they log every call, so a
 * stub can never pass for the real integration in a pod log.
 *
 * Real bindings to replace them:
 * - [PartyKycPort]           → party-service `GET /api/v1/parties/{id}` + kyc-service case status
 * - [KeyInformationDocumentPort] → REAL since #12379 (`DocumentServiceKeyInformationAdapter`);
 *   this stub exists only in `dev`/`test` builds
 * - [SignatureVerificationPort]  → sca-service `POST /api/v1/sca/challenges/{id}/consume`
 * - [TransferCounterpartyPort]   → the inter-provider transfer channel (no service yet)
 * - [FundAdministrationPort]     → pension-fund-service (not built yet, ADR-0334 §1)
 */
@ApplicationScoped
class StubIntegrationSwitch(
    @ConfigProperty(name = "openbank.pension.stub-integrations.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(StubIntegrationSwitch::class.java)

    fun <T> call(port: String, block: () -> T): T {
        if (!enabled) throw IntegrationUnavailableException("$port is not integrated in this environment yet")
        log.warnf("STUB %s answered — not a real integration", port)
        return block()
    }
}

// Prod builds use the real adapter in infrastructure/identity (#12377).
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubPartyKycAdapter(private val stub: StubIntegrationSwitch) : PartyKycPort {
    /** Verified, full capacity, no verified attributes — the applicant's declaration stands. */
    override suspend fun profile(partyId: UUID): KycProfile = stub.call("PartyKycPort") {
        KycProfile(
            KycStatus.VERIFIED,
            fullLegalCapacity = true,
            verifiedBirthDate = null,
            verifiedResidencyCountry = null,
        )
    }
}

/** No relation is ever verified by the stub: guardian applications need the real party-service. */
@ApplicationScoped
class StubPartyRelationAdapter(private val stub: StubIntegrationSwitch) : PartyRelationPort {
    override suspend fun isLegalGuardian(guardianPartyId: UUID, wardPartyId: UUID): Boolean =
        stub.call("PartyRelationPort") { false }
}

@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubKeyInformationDocumentAdapter(private val stub: StubIntegrationSwitch) : KeyInformationDocumentPort {
    override suspend fun generate(request: KidRequest): GeneratedDocument = stub.call("KeyInformationDocumentPort") {
        val content = "${request.templateCode}|${request.applicationId}|${request.strategyCode}"
        val sha = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        GeneratedDocument(documentId = "kid-${UUID.nameUUIDFromBytes(content.toByteArray())}", sha256 = sha)
    }
}

/**
 * Emulates sca-service's contract closely enough that callers cannot rely on anything looser:
 * a challenge is SINGLE-USE (any second spend is REJECTED, whatever it is bound to), and a blank
 * or `rejected-` challenge is refused so tests can drive the refusal path.
 */
// Prod builds use the real adapter in infrastructure/identity (#12377).
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubSignatureVerificationAdapter(private val stub: StubIntegrationSwitch) : SignatureVerificationPort {
    private val spent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun verify(
        partyId: UUID,
        challengeId: String,
        documentSha256: String?,
        operationRef: String,
    ): SignatureOutcome = stub.call("SignatureVerificationPort") {
        when {
            challengeId.isBlank() || challengeId.startsWith(REJECT_PREFIX) -> SignatureOutcome.REJECTED
            !spent.add(challengeId) -> SignatureOutcome.REJECTED
            else -> SignatureOutcome.VERIFIED
        }
    }

    companion object {
        const val REJECT_PREFIX = "rejected-"
    }
}

@ApplicationScoped
class StubTransferCounterpartyAdapter(private val stub: StubIntegrationSwitch) : TransferCounterpartyPort {
    override suspend fun requestTransferIn(request: TransferRequest): CounterpartyReceipt =
        stub.call("TransferCounterpartyPort") {
            CounterpartyReceipt(CounterpartyDispatch.DISPATCHED, "stub-ceding-${request.id}")
        }

    override suspend fun cancelTransferIn(request: TransferRequest) = stub.call("TransferCounterpartyPort") { }

    override suspend fun payTransferOut(
        request: TransferRequest,
        incentiveHistory: List<IncentiveHistoryEntry>,
    ): String = stub.call("TransferCounterpartyPort") { "stub-payment-${request.id}" }
}
