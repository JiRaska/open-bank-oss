// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.sdd.application.port.out.DebtorAccountOwnership
import com.openbank.sdd.application.port.out.DebtorAccountOwnershipPort
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

data class OwnershipVerificationRequestDto(val iban: String, val partyId: UUID)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OwnershipVerificationResponseDto(
    val owned: Boolean = false,
    val active: Boolean = false,
    val accountId: UUID? = null,
)

/** account-service `POST /api/v1/accounts/ownership-verifications` (`account.verifyOwnership`), as service-account-openbank-sdd. */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/accounts/ownership-verifications")
interface AccountOwnershipClient {
    @POST
    fun verify(request: OwnershipVerificationRequestDto): Uni<OwnershipVerificationResponseDto>
}

@ApplicationScoped
class AccountOwnershipAdapter(@RestClient private val client: AccountOwnershipClient) : DebtorAccountOwnershipPort {
    override fun verify(iban: String, partyId: UUID): Uni<DebtorAccountOwnership> =
        client.verify(OwnershipVerificationRequestDto(iban, partyId))
            .map { DebtorAccountOwnership(it.owned, it.active, it.accountId) }
}
