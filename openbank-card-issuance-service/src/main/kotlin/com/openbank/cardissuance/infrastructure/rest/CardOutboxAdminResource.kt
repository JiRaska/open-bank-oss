// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.port.out.CardOutboxRepository
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Operator-triggered recovery for outbox rows parked in terminal `DEAD` (#4005).
 *
 * ### Why an operator endpoint and not a scheduled sweeper
 * Requeueing a DEAD row **re-publishes an event that may already have been delivered**, so
 * whether it is safe is a fact about the consumers, not about this service. Of the two consumers
 * of `openbank.cards.events`:
 *
 * - `openbank-campaign-service` is idempotent — `EnrolmentTriggerConsumer` funnels through
 *   `TriggeredEnrolment.ALREADY_ENROLLED` and `ConversionConsumer` returns early on
 *   `context.alreadyConverted`. A replay there is a no-op.
 * - `openbank-audit-service` is **not**. `AuditConsumer` builds every entry with
 *   `id = UUID.randomUUID()` and calls `repo.save(entry)` with no dedup on the outbox `ce-id`
 *   header; `AuditRepository.save` links the row into a hash chain, and
 *   `audit_entries` is append-only at the database. A replayed card event therefore appends a
 *   **second, permanent, undeletable** audit record of an event that happened once.
 *   (Quoted verbatim again as of #4311. It was briefly reworded into prose because
 *   `identifier-intent-guard` matched the text of a KDoc quoting another service's code as if
 *   this file minted an id itself — the guard was the thing that was wrong, and it now strips
 *   comments before matching. Recorded so the odd wording in git history is not read as a
 *   style choice, and so the workaround does not outlive the bug.)
 *
 * One non-idempotent consumer is enough to make an automatic sweeper the wrong shape: it would
 * silently duplicate audit history on every deploy that happened to find a DEAD row. So the
 * decision stays with a human who can weigh "these events were never delivered at all" (the
 * #4005 case — 24 rows, zero ever published, so replay duplicates nothing) against "these were
 * delivered and then dead-lettered on the acknowledgement" (where it does). `X-Operator-Id` is
 * required so that decision is attributable.
 *
 * A one-shot Flyway migration was the other candidate and is worse on both counts: it runs
 * automatically at boot, it fixes exactly one day's rows and nothing afterwards, and a migration
 * that mutates operational state cannot be re-run once applied (its checksum is frozen).
 */
@Path("/api/v1/cards/outbox")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Card outbox operations", description = "Operator recovery for dead-lettered card events")
class CardOutboxAdminResource(private val outbox: CardOutboxRepository) {

    /**
     * `eventId` is declared **nullable** because it is genuinely optional (absent = requeue every
     * DEAD row). A non-nullable Kotlin `@QueryParam` would not make it required — JAX-RS injects
     * `null` regardless and the request 500s on the synthetic null check, or, in a `suspend fun`
     * where no such check is emitted, proceeds with a null the signature promised was impossible.
     * A malformed value throws `IllegalArgumentException`, which libs-runtime maps to 400; there
     * is deliberately no service-local exception mapper.
     */
    @POST
    @Path("/requeue")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "card.outbox.requeue", resource = "")
    @Operation(
        summary = "Requeue dead-lettered card outbox rows (DEAD -> PENDING)",
        description = "Operator-triggered. Replays events that may already have been delivered — " +
            "openbank-audit-service is not idempotent, so this is never automatic.",
    )
    suspend fun requeueDead(
        @QueryParam("eventId") eventId: String?,
        @HeaderParam("X-Operator-Id") operatorId: String?,
    ): Response {
        requireNotNull(operatorId) { "X-Operator-Id header is required" }
        require(operatorId.isNotBlank()) { "X-Operator-Id header is required" }
        val parsed = eventId?.let {
            require(it.isNotBlank()) { "query parameter 'eventId' must not be blank" }
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("query parameter 'eventId' must be a UUID", e)
            }
        }

        val requeued = outbox.requeueDead(parsed)
        val remaining = outbox.countDead()
        log.warnf(
            "card.outbox.requeue operator=%s event_id=%s requeued=%d dead_remaining=%d",
            operatorId.sanitizeForLog(),
            parsed?.toString() ?: "ALL",
            requeued,
            remaining,
        )
        return Response.ok(OutboxRequeueResponse(requeued = requeued, deadRemaining = remaining)).build()
    }

    // CodeQL java/log-injection (alert 420): operatorId is the raw X-Operator-Id header, so a
    // caller can put CR/LF in it and forge additional log lines (CWE-117). `parsed` needs no
    // such treatment — it is already through UUID.fromString above.
    //
    // Deliberately a member of this class, NOT a top-level extension. A top-level declaration
    // placed between @Path and the class binds the annotation to the FUNCTION: McpEndpoint
    // shipped exactly that shape, RESTEasy never registered the resource, and every POST /mcp
    // answered 404 on a running pod while its unit tests stayed green (#3371).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    private companion object {
        private val log: Logger = Logger.getLogger(CardOutboxAdminResource::class.java)
    }
}

/** How many rows this call moved DEAD -> PENDING, and how many DEAD rows are left behind it. */
data class OutboxRequeueResponse(val requeued: Int, val deadRemaining: Long)
