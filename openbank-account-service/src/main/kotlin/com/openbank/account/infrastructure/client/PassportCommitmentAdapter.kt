// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.PassportCommitment
import com.openbank.account.application.port.out.PassportIntent
import com.openbank.account.application.port.out.PassportOperation
import com.openbank.account.domain.model.ProductRevisionPin
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@ApplicationScoped
@RegisterRestClient(configKey = "product-catalog-api")
@OidcClientFilter("m2m")
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v2/internal/passport-account-admissions")
@Consumes(MediaType.APPLICATION_JSON)
interface PassportAdmissionClient {
    @POST
    fun admit(request: PassportAdmissionRequest): Uni<Void>
}

data class PassportAdmissionRequest(
    val grantId: UUID,
    val operation: String,
    val operationId: UUID,
    val partyId: UUID,
    val revisionId: UUID,
    val binding: String,
)

@ApplicationScoped
class PassportCommitmentAdapter(
    private val pool: Pool,
    private val mapper: ObjectMapper,
    @RestClient private val client: PassportAdmissionClient,
) : PassportCommitment {
    override suspend fun find(key: String): PassportIntent? =
        pool.preparedQuery("SELECT * FROM account_passport_intents WHERE operation_key = $1")
            .execute(Tuple.of(key)).awaitSuspending().firstOrNull()?.toIntent()

    override suspend fun openingFor(accountId: UUID): PassportIntent? =
        pool.preparedQuery("SELECT * FROM account_passport_intents WHERE account_id = $1 AND operation = 'OPEN'")
            .execute(Tuple.of(accountId)).awaitSuspending().firstOrNull()?.toIntent()

    override suspend fun prepare(intent: PassportIntent): PassportIntent {
        pool.preparedQuery(INSERT).execute(
            Tuple.tuple().addString(intent.key).addUUID(intent.operationId)
                .addUUID(intent.accountId).addUUID(intent.grantId).addUUID(intent.partyId)
                .addString(intent.operation.name).addString(intent.binding)
                .addJsonObject(JsonObject(mapper.writeValueAsString(intent.pin))),
        ).awaitSuspending()
        return checkNotNull(find(intent.key)) { "passport operation was not persisted" }
    }

    override suspend fun admit(intent: PassportIntent) {
        try {
            client.admit(
                PassportAdmissionRequest(
                    intent.grantId,
                    intent.operation.name,
                    intent.operationId,
                    intent.partyId,
                    intent.pin.revisionId,
                    intent.binding,
                ),
            ).awaitSuspending()
        } catch (exception: WebApplicationException) {
            if (exception.response.status == CONFLICT_STATUS) {
                throw ResourceConflictException("passport admission refused")
            }
            throw exception
        }
    }

    private fun Row.toIntent(): PassportIntent = PassportIntent(
        key = getString("operation_key"),
        operationId = getUUID("operation_id"),
        accountId = getUUID("account_id"),
        grantId = getUUID("grant_id"),
        partyId = getUUID("party_id"),
        operation = PassportOperation.valueOf(getString("operation")),
        binding = getString("binding"),
        pin = mapper.readValue(getJsonObject("pin").encode(), ProductRevisionPin::class.java),
    )

    private companion object {
        const val CONFLICT_STATUS = 409
        val INSERT = """
            INSERT INTO account_passport_intents (
                operation_key, operation_id, account_id, grant_id, party_id, operation, binding, pin
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            ON CONFLICT (operation_key) DO NOTHING
        """.trimIndent()
    }
}
