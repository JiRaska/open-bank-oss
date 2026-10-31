// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.account.application.port.`in`.VerifyAccountOwnershipQuery
import com.openbank.account.application.port.`in`.VerifyAccountOwnershipUseCase
import com.openbank.libs.audit.AuditChannel
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/** Body of an ownership verification. Fields nullable so an absent one is a 400, never a 500. */
data class OwnershipVerificationRequest(val iban: String? = null, val partyId: UUID? = null)

/** `accountId` is present only when `owned` is true (ADR-0335 D2). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OwnershipVerificationResponse(val owned: Boolean, val active: Boolean, val accountId: UUID? = null)

/**
 * ADR-0335 D2 — the ownership-verification projection. A caller that needs only "does party P
 * own IBAN X and is it active" gets exactly that, under its own action `account.verifyOwnership`,
 * instead of `account.read` and the whole account. The IBAN travels in the body so it stays out of
 * access logs. Every verification is audited with the calling principal and the data subject.
 */
@Path("/api/v1/accounts/ownership-verifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounts", description = "Account management")
class AccountOwnershipResource(
    private val verifyOwnership: VerifyAccountOwnershipUseCase,
    private val auditPublisher: AuditEventPublisher,
) {
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.verifyOwnership", resource = "#request.partyId")
    @Operation(summary = "Verify that a party owns an IBAN and that the account is active")
    suspend fun verify(request: OwnershipVerificationRequest): OwnershipVerificationResponse {
        val iban = requireNotNull(request.iban?.takeIf { it.isNotBlank() }) { "iban is required" }
        val partyId = requireNotNull(request.partyId) { "partyId is required" }
        val verdict = verifyOwnership.verifyOwnership(VerifyAccountOwnershipQuery(iban, partyId))
        auditPublisher.publish(
            AuditEvent(
                actorId = identity.principal?.name ?: "anonymous",
                actorType = "SERVICE",
                operation = "account.verifyOwnership",
                resourceType = "party",
                resourceId = partyId.toString(),
                channel = AuditChannel.API,
                payload = mapOf("owned" to verdict.owned, "active" to verdict.active),
            ),
        )
        return OwnershipVerificationResponse(
            owned = verdict.owned,
            active = verdict.active,
            accountId = verdict.accountId,
        )
    }
}
