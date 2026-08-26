// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.application

import com.openbank.incentive.domain.CodeDigest
import com.openbank.incentive.domain.IncentiveOffer
import com.openbank.incentive.domain.OfferRef
import com.openbank.incentive.domain.OfferStatus
import com.openbank.incentive.domain.PromoReservation
import com.openbank.incentive.domain.StackingPolicy
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ApplicationScoped
@Startup
@Suppress("TooManyFunctions") // One cohesive lifecycle facade; each operation remains an explicit use case.
class IncentiveApplication(
    private val store: IncentiveStore,
    private val metrics: IncentiveMetrics,
    @ConfigProperty(name = "openbank.incentive.code-pepper") configuredPepper: Optional<String>,
    @ConfigProperty(name = "openbank.incentive.reservation-ttl") private val reservationTtl: Duration,
) {
    private val pepper = configuredPepper.filter { it.length >= MIN_PEPPER_LENGTH }
        .orElseThrow { IllegalStateException("PROMO_CODE_PEPPER must contain at least $MIN_PEPPER_LENGTH characters") }
    suspend fun createOffer(command: CreateOffer, actor: String, now: Instant = Instant.now()): IncentiveOffer =
        store.createOffer(
            IncentiveOffer(
                ref = OfferRef(Ids.newId(), command.name, command.version),
                productScope = command.productScope,
                effectiveFrom = command.effectiveFrom,
                expiresAt = command.expiresAt,
                totalLimit = command.totalLimit,
                perPartyLimit = command.perPartyLimit,
                stackingPolicy = command.stackingPolicy,
                status = OfferStatus.DRAFT,
                maker = actor,
            ),
        )

    suspend fun submit(id: UUID, actor: String) = store.submitOffer(id, actor)
    suspend fun publish(id: UUID, actor: String, now: Instant = Instant.now()): IncentiveOffer =
        store.publishOffer(id, actor, now).also {
            metrics.offerPublished()
        }
    suspend fun findOffer(id: UUID) = store.findOffer(id)
    suspend fun listPublishedOffers() = store.listPublishedOffers()

    suspend fun addCodes(id: UUID, codes: List<String>, actor: String, now: Instant = Instant.now()): Int {
        require(codes.isNotEmpty() && codes.none { it.isBlank() }) { "codes are required" }
        return store.addCodes(id, codes.map(::digest).toSet(), actor, now)
    }

    suspend fun reserve(
        offerId: UUID,
        code: String,
        partyRef: String,
        productRef: String,
        key: String,
        actor: String,
        now: Instant = Instant.now(),
    ): PromoReservation = store.reserve(
        offerId,
        digest(code),
        partyRef,
        productRef,
        key,
        actor,
        now,
        now.plus(reservationTtl),
    )

    suspend fun commit(id: UUID, actor: String, now: Instant = Instant.now()) = store.commit(id, actor, now)
    suspend fun release(id: UUID, actor: String, now: Instant = Instant.now()) = store.release(id, actor, now)
    suspend fun expireDue(now: Instant = Instant.now()) = store.expireDue(now)

    private fun digest(code: String): CodeDigest {
        val normalized = code.trim().uppercase()
        require(normalized.length in MIN_CODE_LENGTH..MAX_CODE_LENGTH) { "code length is invalid" }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$pepper:$normalized".toByteArray(StandardCharsets.UTF_8))
        return CodeDigest(bytes.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val MIN_CODE_LENGTH = 8
        const val MAX_CODE_LENGTH = 128
        const val MIN_PEPPER_LENGTH = 32
    }
}

data class CreateOffer(
    val name: String,
    val version: Int,
    val productScope: Set<String>,
    val effectiveFrom: Instant,
    val expiresAt: Instant,
    val totalLimit: Int,
    val perPartyLimit: Int,
    val stackingPolicy: StackingPolicy,
)
