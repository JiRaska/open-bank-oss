// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.pid.application.port.`in`.CandidateSummary
import com.openbank.pid.application.port.`in`.EudiResolutionResult
import com.openbank.pid.application.port.`in`.ResolutionResult
import com.openbank.pid.domain.model.PidClaims
import com.openbank.pid.domain.model.VerificationTrigger
import com.openbank.pid.infrastructure.openid4vp.PresentationExchangeStore.Exchange
import com.openbank.pid.infrastructure.openid4vp.PresentationExchangeStore.Status
import com.openbank.pid.infrastructure.persistence.entity.PresentationExchangeEntity
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class PresentationExchangeRepo : PanacheRepository<PresentationExchangeEntity>

/**
 * Durable [PresentationExchangeStore] (ADR-0094) — the production backing. The single-use nonce spend
 * (PENDING→COMPLETED) is an atomic conditional UPDATE, so a replayed `direct_post` loses the race even
 * across replicas (stronger than the in-memory CAS). The resolved decision is persisted as JSON so the
 * authenticated poll endpoint can collect it from any replica. Every op opens a Panache reactive
 * session because the methods are `suspend` (mirrors PartyRepositoryImpl).
 *
 * Selected by `openbank.pid.eudi.persistence=postgres` (default); else [InMemoryPresentationExchangeStore].
 */
@ApplicationScoped
@IfBuildProperty(name = "openbank.pid.eudi.persistence", stringValue = "postgres")
class PostgresPresentationExchangeStore(
    private val repo: PresentationExchangeRepo,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.pid.eudi.exchange-ttl-seconds", defaultValue = "300")
    private val ttlSeconds: Long,
) : PresentationExchangeStore {

    override suspend fun create(transactionId: String, nonce: String, audience: String, now: Instant): Exchange {
        val expiresAt = now.plusSeconds(ttlSeconds)
        val entity = PresentationExchangeEntity().apply {
            this.transactionId = transactionId
            this.nonce = nonce
            this.audience = audience
            status = Status.PENDING.name
            createdAt = now
            this.expiresAt = expiresAt
        }
        // Drop exchanges that expired over an hour ago (parity with the in-memory grace eviction).
        Panache.withTransaction { repo.delete("expiresAt < ?1", now.minus(EVICT_GRACE_HOURS, ChronoUnit.HOURS)) }
            .awaitSuspending()
        Panache.withTransaction { repo.persist(entity) }.awaitSuspending()
        return Exchange(transactionId, nonce, audience, now, expiresAt, Status.PENDING)
    }

    override suspend fun find(transactionId: String, now: Instant): Exchange? =
        Panache.withSession { repo.find("transactionId", transactionId).firstResult() }
            .awaitSuspending()?.toExchange(now)

    override suspend fun complete(transactionId: String, result: EudiResolutionResult, now: Instant): Boolean =
        Panache.withTransaction {
            repo.update(
                "status = ?1, resultJson = ?2 where transactionId = ?3 and status = ?4 and expiresAt >= ?5",
                Status.COMPLETED.name,
                encodeResult(result),
                transactionId,
                Status.PENDING.name,
                now,
            )
        }.awaitSuspending() > 0

    private fun PresentationExchangeEntity.toExchange(now: Instant): Exchange {
        val stored = Status.valueOf(status)
        val effective = if (stored == Status.PENDING && now.isAfter(expiresAt)) Status.EXPIRED else stored
        return Exchange(
            transactionId = transactionId,
            nonce = nonce,
            audience = audience,
            createdAt = createdAt,
            expiresAt = expiresAt,
            status = effective,
            result = resultJson?.let { decodeResult(it) },
        )
    }

    // ── EudiResolutionResult JSON codec ──
    // The domain stays Jackson-annotation-free; the sealed ResolutionResult is encoded with an
    // explicit discriminator here so a restart/replica can faithfully reload the poll response.

    private fun encodeResult(result: EudiResolutionResult): String {
        val node = objectMapper.createObjectNode()
        // Strip the plaintext national identifier (RČ) before it hits the DB: ADR-0072 forbids RČ at
        // rest (only the blind index is ever persisted). It was already consumed by the resolver during
        // verification and is never surfaced on the poll endpoint, so dropping it is lossless here.
        val persisted = result.claims.copy(nationalIdentifier = null)
        node.set<ObjectNode>("claims", objectMapper.valueToTree(persisted))
        node.set<ObjectNode>("resolution", encodeResolution(result.resolution))
        return objectMapper.writeValueAsString(node)
    }

    private fun encodeResolution(resolution: ResolutionResult): ObjectNode {
        val node = objectMapper.createObjectNode()
        when (resolution) {
            is ResolutionResult.MatchExisting -> node.put("type", MATCH_EXISTING)
                .put("partyId", resolution.partyId.toString())

            is ResolutionResult.NoMatch -> node.put("type", NO_MATCH)

            is ResolutionResult.NeedsManualVerification -> {
                node.put("type", NEEDS_MANUAL)
                node.put("caseId", resolution.caseId?.toString())
                node.put("trigger", resolution.trigger.name)
                val candidates = node.putArray("candidates")
                resolution.candidates.forEach { c ->
                    candidates.addObject()
                        .put("partyId", c.partyId.toString())
                        .put("nameMasked", c.nameMasked)
                        .put("birthYear", c.birthYear)
                }
            }
        }
        return node
    }

    private fun decodeResult(json: String): EudiResolutionResult {
        val node = objectMapper.readTree(json)
        val claims = objectMapper.treeToValue(node.get("claims"), PidClaims::class.java)
        return EudiResolutionResult(claims, decodeResolution(node.get("resolution")))
    }

    private fun decodeResolution(node: com.fasterxml.jackson.databind.JsonNode): ResolutionResult =
        when (node.get("type").asText()) {
            MATCH_EXISTING -> ResolutionResult.MatchExisting(UUID.fromString(node.get("partyId").asText()))
            NO_MATCH -> ResolutionResult.NoMatch
            NEEDS_MANUAL -> ResolutionResult.NeedsManualVerification(
                caseId = node.get("caseId")?.takeUnless { it.isNull }?.let { UUID.fromString(it.asText()) },
                candidates = node.get("candidates").orEmpty().map { c ->
                    CandidateSummary(
                        partyId = UUID.fromString(c.get("partyId").asText()),
                        nameMasked = c.get("nameMasked").asText(),
                        birthYear = c.get("birthYear").asInt(),
                    )
                },
                trigger = VerificationTrigger.valueOf(node.get("trigger").asText()),
            )
            else -> error("unknown resolution type")
        }

    private fun com.fasterxml.jackson.databind.JsonNode?.orEmpty(): List<com.fasterxml.jackson.databind.JsonNode> =
        this?.toList() ?: emptyList()

    private companion object {
        const val EVICT_GRACE_HOURS = 1L
        const val MATCH_EXISTING = "MATCH_EXISTING"
        const val NO_MATCH = "NO_MATCH"
        const val NEEDS_MANUAL = "NEEDS_MANUAL_VERIFICATION"
    }
}
