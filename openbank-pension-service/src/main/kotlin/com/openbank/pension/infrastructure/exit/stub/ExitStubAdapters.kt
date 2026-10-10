// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.stub

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.application.exit.BeneficiaryVerificationPort
import com.openbank.pension.application.exit.ClaimantKyc
import com.openbank.pension.application.exit.OwnAccountVerificationPort
import com.openbank.pension.application.exit.ParticipantNotificationPort
import com.openbank.pension.application.exit.PaymentOrder
import com.openbank.pension.application.exit.PayoutPaymentPort
import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.exit.TaxWithholdingPort
import io.quarkus.arc.DefaultBean
import io.quarkus.arc.profile.IfBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * STUB adapters for the exit slice (ADR-0334 S5). Each one stands in for a service whose API this
 * slice needs and which is not wired yet; every one is a `@DefaultBean`, so the real adapter
 * replaces it by merely existing. Follow-ups are listed in the S5 PR.
 *
 * The three CHECKS (SCA, own account, beneficiary KYC) FAIL CLOSED unless a profile explicitly
 * turns the stub on — a deployment that forgot to wire sca-service refuses every termination
 * rather than accepting an unsigned one. Only %dev and %test enable them.
 */

/**
 * The money-moving stubs below (tax remittance, payout payment) answer only when
 * `openbank.pension.exit.stub.checks-accept=true` (%dev / %test). Otherwise they REFUSE: a deployed
 * pod with no real rail must fail a death settlement or payout loudly, never record a payment that
 * never left (the PushResult.skipped lesson, ADR-0252). ADR-0334 S8.
 */
class ExitStubRailUnavailableException(port: String) :
    IllegalStateException("$port is not integrated in this environment (stub refuses outside dev/test)")

private fun requireStubRail(accept: Boolean, port: String) {
    if (!accept) throw ExitStubRailUnavailableException(port)
}

@DefaultBean
@ApplicationScoped
class StubTaxWithholdingAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.checks-accept", defaultValue = "false")
    private val accept: Boolean,
) : TaxWithholdingPort {
    val remitted = ConcurrentHashMap<String, BigDecimal>()

    override suspend fun remit(contractId: UUID, kind: String, amount: BigDecimal, idempotencyKey: String) {
        requireStubRail(accept, "TaxWithholdingPort")
        remitted.putIfAbsent(idempotencyKey, amount)
    }
}

/**
 * domestic-payment stand-in: deduplicates by idempotency key exactly as the real scheme gateway must.
 * dev/test only (#12378): the real adapter is `DomesticPayoutPaymentAdapter`, the only bean in a
 * prod build.
 */
@IfBuildProfile(anyOf = ["dev", "test"])
@DefaultBean
@ApplicationScoped
class StubPayoutPaymentAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.checks-accept", defaultValue = "false")
    private val accept: Boolean,
) : PayoutPaymentPort {
    val orders = ConcurrentHashMap<String, PaymentOrder>()
    private val refs = ConcurrentHashMap<String, String>()

    override suspend fun pay(order: PaymentOrder): String {
        requireStubRail(accept, "PayoutPaymentPort")
        orders.putIfAbsent(order.idempotencyKey, order)
        return refs.computeIfAbsent(order.idempotencyKey) { "STUB-PAY-${Ids.randomId()}" }
    }
}

@DefaultBean
@ApplicationScoped
class StubScaVerificationAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.checks-accept", defaultValue = "false")
    private val accept: Boolean,
) : ScaVerificationPort {
    private val log = Logger.getLogger(StubScaVerificationAdapter::class.java)
    private val consumed = ConcurrentHashMap.newKeySet<String>()

    override suspend fun verify(
        partyId: UUID,
        challengeId: String,
        documentSha256: String,
        operation: ScaOperation,
    ): Boolean {
        if (!accept) {
            log.warn("SCA stub is fail-closed (openbank.pension.exit.stub.checks-accept=false): sca-service not wired")
            return false
        }
        // Single use, like sca-service's consume (RTS Art. 5): a replayed challenge is refused.
        return challengeId.isNotBlank() && consumed.add(challengeId)
    }
}

@DefaultBean
@ApplicationScoped
class StubOwnAccountVerificationAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.checks-accept", defaultValue = "false")
    private val accept: Boolean,
) : OwnAccountVerificationPort {
    override suspend fun isOwnVerifiedAccount(partyId: UUID, iban: String): Boolean = accept
}

@DefaultBean
@ApplicationScoped
class StubBeneficiaryVerificationAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.checks-accept", defaultValue = "false")
    private val accept: Boolean,
) : BeneficiaryVerificationPort {
    override suspend fun verify(kyc: ClaimantKyc): Boolean = accept && kyc.identityDocumentRef.isNotBlank()
}

/**
 * notification-service stand-in (ADR-0334 S8): records the payout-account-change notice. Refuses
 * outside dev/test, so a deployed pod cannot change a payout account without telling the
 * participant — the change is refused instead. Real target: notification-service on the
 * participant's verified channel (follow-up, #12350).
 */
@DefaultBean
@ApplicationScoped
class StubParticipantNotificationAdapter(
    @param:ConfigProperty(name = "openbank.pension.exit.stub.checks-accept", defaultValue = "false")
    private val accept: Boolean,
) : ParticipantNotificationPort {
    val sent = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun payoutAccountChanged(
        partyId: UUID,
        contractId: UUID,
        payoutId: UUID,
        accountLast4: String,
        effectiveFrom: LocalDate,
    ) {
        requireStubRail(accept, "ParticipantNotificationPort")
        sent += "$payoutId|$accountLast4|$effectiveFrom"
    }
}
