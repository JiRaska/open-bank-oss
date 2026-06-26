// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.statement.application.port.out.AccountRegistry
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Builds the local [AccountRegistry] by consuming the account-service event stream
 * (topic openbank.accounts.account.created). The registry is the enumeration source for the
 * scheduled monthly period-close ([PeriodCloseScheduler]); account-service exposes no "all
 * accounts" endpoint and owns its own DB (ADR-0002), so a projection is the idiomatic path.
 *
 * Filters by JSON `eventType`; only `AccountCreated` matters here. Delivery is at-least-once and the
 * registry upsert is idempotent, so redelivery (including the earliest-offset backfill on first
 * deploy) is safe. A DB failure propagates so the message is nacked and redelivered; only a poison
 * pill (unparseable / missing field) is swallowed so it cannot wedge the stream.
 */
@ApplicationScoped
class AccountRegistryConsumer(private val registry: AccountRegistry, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(AccountRegistryConsumer::class.java)

    @Incoming("account-events-in")
    fun consume(payload: String): Uni<Void> {
        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable account event, skipping: %s", payload.take(200))
            return Uni.createFrom().voidItem()
        }

        if (node["eventType"]?.asText() != EVENT_TYPE) return Uni.createFrom().voidItem()

        return try {
            val accountId = UUID.fromString(node["aggregateId"].asText())
            val partyId = UUID.fromString(node["partyId"].asText())
            val currency = node["currency"].asText()
            registry.upsertOpen(accountId, partyId, currency)
        } catch (e: Exception) {
            log.errorf(e, "Malformed AccountCreated, skipping: %s", payload.take(200))
            Uni.createFrom().voidItem()
        }
    }

    companion object {
        private const val EVENT_TYPE = "AccountCreated"
    }
}
