// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.pensionfund.application.port.ClassificationReceipt
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.ClassificationCorrectionStatus
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.PositionClassificationCorrection
import com.openbank.pensionfund.domain.model.effectiveClasses
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.UUID

/**
 * The operator route that classifies recorded positions (#12425): positions recorded before the
 * instrument class existed are UNCLASSIFIED, and so is one a depositary feed sent without a class.
 * A maker proposes, a different checker approves; nothing changes a position's class otherwise.
 */
@ApplicationScoped
class PositionClassificationService(private val store: PensionFundStore, private val clock: Clock) {

    /** The positions a NAV was struck on, each with the class it carries now. */
    suspend fun positions(navId: UUID): List<NavPosition> {
        store.nav(navId) ?: throw NotFoundException("NAV $navId not found")
        val recorded = store.navPositions(navId)
        return effectiveClasses(recorded, store.classificationCorrections(recorded.map { it.id }))
    }

    suspend fun propose(
        request: ClassificationCorrectionRequest,
        actor: String,
        key: String,
    ): PositionClassificationCorrection = execute(
        "propose",
        actor,
        key,
        "${request.positionId}:${request.toClass}:${request.reason.length}:${request.reason}",
    ) {
        val position = store.navPosition(request.positionId)
            ?: throw NotFoundException("position ${request.positionId} not found")
        val corrections = store.classificationCorrections(listOf(position.id))
        check(corrections.none { it.status == ClassificationCorrectionStatus.PROPOSED }) {
            "position ${position.id} already has a correction awaiting approval"
        }
        val current = effectiveClasses(listOf(position), corrections).single().instrumentClass
        val correction = PositionClassificationCorrection(
            id = Ids.newId(),
            positionId = position.id,
            navId = position.navId,
            fromClass = current,
            toClass = request.toClass,
            reason = request.reason,
            proposedBy = actor,
            proposedAt = clock.instant(),
        )
        correction
    }

    suspend fun approve(id: UUID, actor: String, key: String): PositionClassificationCorrection =
        execute("approve", actor, key, id.toString()) {
            val correction = correction(id)
            val position = checkNotNull(store.navPosition(correction.positionId))
            val current = effectiveClasses(listOf(position), store.classificationCorrections(listOf(position.id)))
                .single().instrumentClass
            // The class the maker saw is the one the checker approves a change FROM; anything else is stale.
            check(current == correction.fromClass) {
                "position ${position.id} is now $current, not ${correction.fromClass}; propose again"
            }
            correction.approve(actor, clock.instant())
        }

    suspend fun reject(id: UUID, actor: String, key: String): PositionClassificationCorrection =
        execute("reject", actor, key, id.toString()) { correction(id).reject(actor, clock.instant()) }

    /** The immutable replay snapshot and the mutation commit together, including after a lost response. */
    // Recover both domain-check races and driver failures only when a durable receipt exists.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(
        operation: String,
        actor: String,
        key: String,
        payload: String,
        action: suspend () -> PositionClassificationCorrection,
    ): PositionClassificationCorrection {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Idempotency-Key must contain 1 to 128 characters" }
        val scopedKey = RequestFingerprint.of("POST", operation, "${actor.length}:$actor${key.length}:$key")
        val fingerprint = RequestFingerprint.of("POST", operation, payload)
        fun replay(receipt: ClassificationReceipt): PositionClassificationCorrection {
            if (receipt.fingerprint != fingerprint) throw IdempotencyKeyReusedException()
            return receipt.result
        }
        store.classificationReceipt(scopedKey)?.let { return replay(it) }
        try {
            val result = action()
            store.commit(
                StoreChanges(
                    classificationCorrections = listOf(result),
                    classificationReceipt = ClassificationReceipt(scopedKey, fingerprint, result),
                ),
            )
            return result
        } catch (failure: RuntimeException) {
            // A concurrent winner may commit between our initial read and the unique-key insert,
            // or before a domain check. Replay only its durable receipt; never swallow other failures.
            val winner = store.classificationReceipt(scopedKey) ?: throw failure
            return replay(winner)
        }
    }

    private suspend fun correction(id: UUID) =
        store.classificationCorrection(id) ?: throw NotFoundException("classification correction $id not found")
    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}
