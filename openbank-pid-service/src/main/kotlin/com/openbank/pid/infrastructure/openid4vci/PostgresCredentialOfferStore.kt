// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.infrastructure.openid4vci.CredentialOfferStore.Offer
import com.openbank.pid.infrastructure.openid4vci.CredentialOfferStore.Status
import com.openbank.pid.infrastructure.persistence.entity.CredentialOfferEntity
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant

@ApplicationScoped
class CredentialOfferRepo : PanacheRepository<CredentialOfferEntity>

/**
 * Durable [CredentialOfferStore] (ADR-0094) — the production backing. The single-use transitions
 * (OFFERED→AUTHORIZED, AUTHORIZED→ISSUED) are atomic conditional UPDATEs, so a replayed redemption /
 * credential request loses the race even across replicas (stronger than the in-memory CAS). Every op
 * opens a Panache reactive session because the methods are `suspend` (mirrors PartyRepositoryImpl).
 *
 * Selected by `openbank.pid.eudi.persistence=postgres` (the default); else [InMemoryCredentialOfferStore].
 */
@ApplicationScoped
@IfBuildProperty(name = "openbank.pid.eudi.persistence", stringValue = "postgres")
class PostgresCredentialOfferStore(
    private val repo: CredentialOfferRepo,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.pid.eudi.issuer.offer-ttl-seconds", defaultValue = "600")
    private val ttlSeconds: Long,
) : CredentialOfferStore {

    override suspend fun create(preAuthCode: String, claims: OfferedClaims, now: Instant): Offer {
        val expiresAt = now.plusSeconds(ttlSeconds)
        val entity = CredentialOfferEntity().apply {
            this.preAuthCode = preAuthCode
            status = Status.OFFERED.name
            claimsJson = objectMapper.writeValueAsString(claims)
            createdAt = now
            this.expiresAt = expiresAt
        }
        // Bound the table: drop offers past their TTL (parity with the in-memory opportunistic evict).
        Panache.withTransaction { repo.delete("expiresAt < ?1", now) }.awaitSuspending()
        Panache.withTransaction { repo.persist(entity) }.awaitSuspending()
        return Offer(preAuthCode, claims, now, expiresAt, Status.OFFERED)
    }

    override suspend fun authorize(preAuthCode: String, accessToken: String, cNonce: String, now: Instant): Offer? {
        val rows = Panache.withTransaction {
            repo.update(
                "status = ?1, accessToken = ?2, cNonce = ?3 where preAuthCode = ?4 and status = ?5 and expiresAt >= ?6",
                Status.AUTHORIZED.name,
                accessToken,
                cNonce,
                preAuthCode,
                Status.OFFERED.name,
                now,
            )
        }.awaitSuspending()
        if (rows == 0) return null // unknown / already-redeemed / expired
        return Panache.withSession { repo.find("preAuthCode", preAuthCode).firstResult() }
            .awaitSuspending()?.toOffer(now)
    }

    override suspend fun findByAccessToken(accessToken: String, now: Instant): Offer? =
        Panache.withSession { repo.find("accessToken", accessToken).firstResult() }
            .awaitSuspending()?.toOffer(now)

    override suspend fun markIssued(accessToken: String, now: Instant): Boolean = Panache.withTransaction {
        repo.update(
            "status = ?1 where accessToken = ?2 and status = ?3 and expiresAt >= ?4",
            Status.ISSUED.name,
            accessToken,
            Status.AUTHORIZED.name,
            now,
        )
    }.awaitSuspending() > 0

    private fun CredentialOfferEntity.toOffer(now: Instant): Offer {
        val stored = Status.valueOf(status)
        // A still-live offer past its TTL reads as EXPIRED (so the AUTHORIZED check fails) without a
        // write — the markIssued UPDATE already guards `expiresAt >= now`, so this is read-only parity.
        val effective = if (stored != Status.ISSUED && now.isAfter(expiresAt)) Status.EXPIRED else stored
        return Offer(
            preAuthCode = preAuthCode,
            claims = objectMapper.readValue(claimsJson, OfferedClaims::class.java),
            createdAt = createdAt,
            expiresAt = expiresAt,
            status = effective,
            cNonce = cNonce,
            accessToken = accessToken,
        )
    }
}
