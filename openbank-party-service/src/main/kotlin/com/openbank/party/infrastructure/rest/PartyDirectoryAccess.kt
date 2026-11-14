// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.security.PiiMask
import io.quarkus.security.identity.SecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken

internal class PartyDirectoryAccess(identity: SecurityIdentity) {
    val machine = ServiceAccountIdentity.verifiedClientId(identity) != null
    val canSearchNames = identity.hasRole("ROLE_OPERATOR") ||
        identity.hasRole("ROLE_ADMIN") ||
        identity.hasRole("ROLE_KYC")
    private val canSeeLegalName = canSearchNames || identity.hasRole("ROLE_RISK") || identity.hasRole("ROLE_COMPLIANCE")

    val revealActor: String? =
        (identity.principal as? JsonWebToken)?.subject?.takeIf {
            ServiceAccountIdentity.isInteractiveHumanToken(identity.principal) &&
                (identity.hasRole("ROLE_ADMIN") || identity.hasRole("ROLE_KYC"))
        }

    fun maskList(page: Map<String, Any>): Map<String, Any> {
        val items = checkNotNull(page["items"] as? List<*>) { "Party list is missing items" }
        return page.filterKeys { it in LIST_METADATA } + ("items" to items.map { maskSummary(it as Map<*, *>) })
    }

    fun maskSummary(party: Map<*, *>): Map<String, Any?> = mapOf(
        "id" to party["id"],
        "partyType" to party["partyType"],
        "classification" to party["classification"],
        "status" to party["status"],
        "legalName" to if (canSeeLegalName) party["legalName"] else mask(party["legalName"] as? String, PiiMask::name),
        "tradingName" to party["tradingName"].takeIf { canSearchNames },
        "email" to mask(party["email"] as? String, PiiMask::email),
        "kycStatus" to party["kycStatus"],
        "createdAt" to party["createdAt"],
    ).filterValues { it != null }

    fun maskDetail(party: Map<String, Any?>): Map<String, Any?> = maskSummary(party) + mapOf(
        "phone" to (party["phone"] as? String)?.let { mask(it, PiiMask::phone) },
        "updatedAt" to party["updatedAt"],
        "mergedIntoPartyId" to party["mergedIntoPartyId"],
        "piiMasked" to true,
        "piiRevealAllowed" to (revealActor != null),
    ).filterValues { it != null }

    private fun mask(value: String?, mask: (String?) -> String): String =
        mask(value).takeIf { it.isNotBlank() && it != value } ?: "***"

    private companion object {
        val LIST_METADATA = setOf("page", "size", "total", "statusFilter")
    }
}
