// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.application.usecase

import com.openbank.swift.application.port.out.RedriveBackoff
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.application.port.out.SwiftSettlementRedrivePort
import com.openbank.swift.domain.model.SwiftStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * #13686: re-drive the settlement of a scheme-accepted MT103 whose booking is unproven.
 *
 * swift-service books once, right after ACSC. A transport failure on that single call used to leave the
 * debit unbooked for good. This re-enters ONLY the settlement step ([SwiftService.settleAccepted]): the
 * scheme is never called again, the key is the same `swift-settlement-<messageId>`, and the request is
 * rebuilt from the stored message (32A value date), so transaction-service either books it for the first
 * time or replays the booking it already has.
 *
 * Three independent guards against booking twice:
 * 1. the durable claim ([SwiftSettlementRedrivePort.claim]): one caller per message at a time, so concurrent
 *    sweeps (two pods, an overlapping tick) make one transaction-service call, not two;
 * 2. the idempotency key: a call that does land twice gets the stored booking back;
 * 3. the locked completion write (`writeBooking`): one proof and one COMPLETED event per message.
 */
@ApplicationScoped
class SwiftSettlementRedriveService(
    private val repo: SwiftRepository,
    private val redrive: SwiftSettlementRedrivePort,
    private val swift: SwiftService,
    private val clock: Clock,
    private val minAge: Duration,
    private val lease: Duration,
    private val backoff: RedriveBackoff,
) {
    @Inject
    constructor(
        repo: SwiftRepository,
        redrive: SwiftSettlementRedrivePort,
        swift: SwiftService,
        clock: Clock,
        @ConfigProperty(name = "openbank.swift.settlement-redrive.min-age", defaultValue = "PT2M")
        minAge: Duration,
        @ConfigProperty(name = "openbank.swift.settlement-redrive.lease", defaultValue = "PT2M")
        lease: Duration,
        backoff: RedriveBackoffConfig,
    ) : this(repo, redrive, swift, clock, minAge, lease, backoff.toBackoff())

    private val log = Logger.getLogger(SwiftSettlementRedriveService::class.java)

    enum class Result { SETTLED, RESCHEDULED, EXHAUSTED, NOT_CLAIMED }

    /** Candidate ids for one sweep; each still has to win [redrive]'s claim. */
    suspend fun due(limit: Int): List<UUID> {
        val now = Instant.now(clock)
        return redrive.dueIds(now, now.minus(minAge), limit)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun redrive(id: UUID): Result {
        val now = Instant.now(clock)
        val token = UUID.randomUUID()
        if (!redrive.claim(id, token, now, now.minus(minAge), now.plus(lease))) return Result.NOT_CLAIMED
        val settled = try {
            val message = repo.findById(id) ?: error("claimed SWIFT message $id is missing")
            swift.settleAccepted(message).status == SwiftStatus.COMPLETED
        } catch (ex: CancellationException) {
            // The claim lapses with its lease; the next sweep picks the message up again.
            throw ex
        } catch (ex: Exception) {
            log.warnf(ex, "Settlement re-drive of SWIFT message %s failed; counting the attempt", id)
            false
        }
        if (settled) {
            redrive.release(id, token)
            log.infof("Settlement re-drive booked SWIFT message %s", id)
            return Result.SETTLED
        }
        val outcome = redrive.reschedule(id, token, Instant.now(clock), backoff) ?: return Result.NOT_CLAIMED
        if (outcome.exhausted) {
            log.errorf(
                "Settlement re-drive of SWIFT message %s exhausted after %d attempts; booking clock stays open",
                id,
                outcome.attempts,
            )
            return Result.EXHAUSTED
        }
        log.warnf(
            "Settlement re-drive of SWIFT message %s unproven (attempt %d); next at %s",
            id,
            outcome.attempts,
            outcome.nextAt,
        )
        return Result.RESCHEDULED
    }
}

/** The three backoff knobs, bundled so the service's injection constructor stays under detekt's limit. */
@ApplicationScoped
class RedriveBackoffConfig {
    @ConfigProperty(name = "openbank.swift.settlement-redrive.backoff-base", defaultValue = "PT2M")
    lateinit var base: Duration

    @ConfigProperty(name = "openbank.swift.settlement-redrive.backoff-max", defaultValue = "PT1H")
    lateinit var max: Duration

    @ConfigProperty(name = "openbank.swift.settlement-redrive.max-attempts", defaultValue = "10")
    var maxAttempts: Int = 0

    fun toBackoff() = RedriveBackoff(base, max, maxAttempts)
}
