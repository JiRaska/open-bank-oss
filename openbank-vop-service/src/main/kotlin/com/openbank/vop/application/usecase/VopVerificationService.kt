// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.application.usecase

import com.openbank.libs.domain.account.Iban
import com.openbank.vop.application.port.`in`.VerifyPayeeCommand
import com.openbank.vop.application.port.`in`.VerifyPayeeUseCase
import com.openbank.vop.application.port.out.AccountHolderNameLookupPort
import com.openbank.vop.application.port.out.NameLookupUnavailableException
import com.openbank.vop.application.port.out.VopMetricsPort
import com.openbank.vop.application.port.out.VopRoute
import com.openbank.vop.application.port.out.VopSchemeRoutingPort
import com.openbank.vop.application.port.out.VopVerificationRecordPort
import com.openbank.vop.domain.match.VopNameMatchPolicy
import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Orchestrates Verification of Payee (ADR-0171). Every *decision* lives in the framework-free
 * [VopNameMatchPolicy]; this use case only routes, wires the ports, and records the evidence.
 *
 * Two routes (ADR-0171 §4):
 * - **Ours** — resolve the holder name locally (account-service → party-service) and match.
 * - **External** — hand to the EPC scheme port, which today has no link and honestly answers
 *   NO_DATA / NO_SCHEME_CONNECTIVITY rather than fabricating a result.
 *
 * **Fail-open, loudly.** A lookup failure yields NO_DATA + a warning, never a hold and never a
 * silent MATCH. This is the deliberate inverse of the sanctions gate (ADR-0032), which fails
 * closed: a sanctions miss is a legal breach, whereas refusing every payment during a VoP outage
 * would itself breach the IPR execution-time obligation. Do not "fix" this to match its
 * neighbour.
 *
 * "Loudly" is what [VopMetricsPort] is for. Failing open means a total downstream outage looks, from
 * every angle except the meters, exactly like a bank whose customers all pay strangers: the caller
 * gets a well-formed `NO_DATA`, the payment proceeds, and the only trace is a WARN line.
 */
@ApplicationScoped
class VopVerificationService(
    private val nameLookup: AccountHolderNameLookupPort,
    private val schemeRouting: VopSchemeRoutingPort,
    private val records: VopVerificationRecordPort,
    private val metrics: VopMetricsPort,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.vop.domestic-iban-prefixes", defaultValue = "CZ")
    private val domesticIbanPrefixes: List<String>,
    @ConfigProperty(name = "openbank.vop.max-edit-distance", defaultValue = "1")
    private val maxEditDistance: Int,
) : VerifyPayeeUseCase {

    private val log = Logger.getLogger(VopVerificationService::class.java)
    private val policy: VopNameMatchPolicy by lazy { VopNameMatchPolicy(maxEditDistance) }

    override fun verify(command: VerifyPayeeCommand): Uni<VopVerification> {
        val iban = Iban.of(command.iban)
        val route = if (isDomestic(iban)) VopRoute.DOMESTIC else VopRoute.EXTERNAL
        // `deferred` so the stopwatch starts on SUBSCRIPTION, not on assembly: a Uni built here and
        // subscribed later would otherwise be timed from the wrong instant.
        return Uni.createFrom().deferred {
            val startedAt = System.nanoTime()
            val resolved = when (route) {
                VopRoute.DOMESTIC -> resolveLocally(iban, command.payeeName)
                VopRoute.EXTERNAL -> resolveExternally(iban, command)
            }
            resolved
                .call { verification -> record(iban, command, verification) }
                .onItem().invoke { verification ->
                    metrics.verificationCompleted(route, verification, Duration.ofNanos(System.nanoTime() - startedAt))
                }
        }
    }

    private fun resolveLocally(iban: Iban, suppliedName: String): Uni<VopVerification> =
        nameLookup.lookupHolderName(iban.value)
            .map { holderName ->
                if (holderName.isNullOrBlank()) {
                    // The IBAN is in our country's space but we hold no name for it — either it is
                    // not our account or the account carries no party name. Both are NO_DATA; we
                    // deliberately do NOT distinguish them on the wire, because "not our account"
                    // is itself information an enumerator would want (see the threat model).
                    noData(VopNoDataReason.ACCOUNT_NOT_FOUND)
                } else {
                    decide(suppliedName, holderName)
                }
            }
            .onFailure(NameLookupUnavailableException::class.java).recoverWithItem { failure ->
                log.warnf(failure, "Payee name lookup unavailable for %s; answering NO_DATA", iban.countryCode)
                noData(VopNoDataReason.LOOKUP_UNAVAILABLE)
            }

    private fun resolveExternally(iban: Iban, command: VerifyPayeeCommand): Uni<VopVerification> =
        schemeRouting.verifyExternal(iban.value, command.payeeName)
            .onFailure().recoverWithItem { failure ->
                log.warnf(failure, "VoP scheme routing failed for %s; answering NO_DATA", iban.countryCode)
                noData(VopNoDataReason.LOOKUP_UNAVAILABLE)
            }

    /**
     * Turn a name comparison into a verification. The matched name rides along ONLY for
     * CLOSE_MATCH — on NO_MATCH we must never echo a name the payer did not already know
     * (ADR-0171 §6); [VopVerification] enforces this invariant too.
     */
    private fun decide(suppliedName: String, holderName: String): VopVerification =
        when (val outcome = policy.match(suppliedName, holderName)) {
            VopOutcome.CLOSE_MATCH -> VopVerification(
                outcome = outcome,
                matchedName = holderName,
                verifiedAt = now(),
            )
            else -> VopVerification(outcome = outcome, verifiedAt = now())
        }

    private fun noData(reason: VopNoDataReason) =
        VopVerification(outcome = VopOutcome.NO_DATA, noDataReason = reason, verifiedAt = now())

    private fun isDomestic(iban: Iban): Boolean =
        domesticIbanPrefixes.any { it.trim().equals(iban.countryCode, ignoreCase = true) }

    private fun record(iban: Iban, command: VerifyPayeeCommand, verification: VopVerification): Uni<Void> =
        records.record(
            ibanHash = sha256(iban.value),
            suppliedNameHash = sha256(command.payeeName),
            verification = verification,
            requestedBy = command.requestedBy,
        )

    private fun now(): Instant = clock.instant()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
