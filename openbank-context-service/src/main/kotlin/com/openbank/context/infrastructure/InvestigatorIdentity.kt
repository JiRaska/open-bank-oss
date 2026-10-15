// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.domain.Investigator
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity

/** Preserve verified JWT classification through the context service's second policy check. */
internal fun SecurityIdentity.investigator(): Investigator = Investigator(
    id = principal.name,
    roles = roles.sorted(),
    serviceAccount = !ServiceAccountIdentity.isVerifiedPerson(this),
    clientId = ServiceAccountIdentity.verifiedClientId(this),
)
