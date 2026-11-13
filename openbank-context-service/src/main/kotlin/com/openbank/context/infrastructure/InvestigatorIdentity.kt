// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.domain.Investigator
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken

/** Preserve verified JWT classification through the context service's second policy check. */
internal fun SecurityIdentity.investigator(): Investigator = Investigator(
    id = principal.name,
    roles = roles.sorted(),
    serviceAccount = !isInteractiveInvestigator(),
    clientId = ServiceAccountIdentity.verifiedClientId(this),
)

/** Staff roles alone and unreadable machine notes cannot establish an interactive investigator. */
private fun SecurityIdentity.isInteractiveInvestigator(): Boolean = runCatching {
    val jwt = principal as? JsonWebToken ?: return@runCatching false
    val claims = jwt.claimNames ?: return@runCatching false
    "client_id" !in claims && "clientId" !in claims && ServiceAccountIdentity.isInteractiveHumanToken(jwt)
}.getOrDefault(false)
