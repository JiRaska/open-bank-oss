// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.application.port.`in`.CopilotChatUseCase
import com.openbank.copilot.domain.ChatOutcome
import com.openbank.copilot.domain.ChatTurn
import com.openbank.copilot.infrastructure.observability.CopilotMetricsAdapter
import com.openbank.libs.authz.Authorize
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Customer assistant chat surface (ADR-0089). `@Authenticated` — never `@PermitAll`; the customer
 * identity is the OIDC `sub`, taken from the session JWT (via the edge, ADR-0065), NEVER from the
 * request body or prompt. The reasoning loop runs server-side; the model is never called from the
 * device.
 *
 * Like the admin assistant (ADR-0031), this is NOT a `suspend` resource method: a suspend method
 * runs on the Vert.x event loop, where the governed loop's blocking clients (OPA PDP, downstream
 * REST tool calls in Phase 2) throw BlockingNotAllowedException and the gate fails closed. We
 * bridge to the suspending service with `runBlocking` on the worker thread, keeping CDI / audit /
 * SecurityIdentity context on this thread.
 */
@Path("/api/v1/copilot")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
class CopilotChatResource {

    @Inject
    lateinit var chat: CopilotChatUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var metrics: CopilotMetricsAdapter

    @POST
    @Path("/chat")
    @Authenticated
    @Authorize(action = "copilot.chat")
    fun chat(request: ChatRequest): Response = runBlocking {
        val customerId = customerSubject()
            ?: return@runBlocking Response.status(Response.Status.UNAUTHORIZED)
                .entity(mapOf("error" to "authenticated token is missing a usable subject"))
                .build()

        val turn = ChatTurn(
            conversationId = request.conversationId ?: "new",
            message = request.message,
            currentThemeSpec = request.themeSpec,
        )
        when (val outcome = chat.handle(turn, customerId, erasureIdentity())) {
            is ChatOutcome.Disabled -> {
                metrics.recordChatRequest(CopilotMetricsAdapter.OUTCOME_DISABLED)
                Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity(mapOf("error" to "copilot is disabled (feature flag off)"))
                    .build()
            }
            is ChatOutcome.Replied -> {
                metrics.recordChatRequest(CopilotMetricsAdapter.OUTCOME_REPLIED)
                Response.ok(outcome.reply).build()
            }
        }
    }

    /** OIDC `sub` (stable Keycloak user id). For a bearer token the principal is the JsonWebToken. */
    private fun customerSubject(): String? {
        val principal = identity.principal
        return (principal as? JsonWebToken)?.subject?.takeIf { it.isNotBlank() }
            ?: principal?.name?.takeIf { it.isNotBlank() }
    }

    /**
     * The identity `PARTY_ERASED` will arrive with: the token's `party_id` claim, or **null** when
     * the token carries none.
     *
     * Recorded on the conversation row so erasure can find it; it is NOT the storage key, so this
     * changes nothing about which conversation a customer resumes. Resolving it here rather than in
     * the consumer is the whole point: at erasure time the Keycloak user is gone, so the `sub` ->
     * `party_id` mapping no longer exists to be looked up. Measured against the deployed customers
     * realm, `sub` equalled `party_id` for 0 of 35 users (#3881) — this is not a corner case.
     *
     * ## Why the `sub` fallback was removed (#4175)
     *
     * It never widened the erasure's reach by a single row. `ConversationStore.deleteForParty`
     * already matches `partyId = ?1 OR customerId = ?1`, and `customerId` IS the `sub` — so writing
     * `sub` into the party-id column duplicated an arm the delete performs anyway. What it did do
     * was disguise the failure: the column is *defined* as the erasure identity, so a row holding a
     * fabricated one is indistinguishable, in the table and in every query over it, from a row that
     * genuinely resolved a party. That is the whole reason a `PARTY_ERASED` carrying the true party
     * id can no-op invisibly for these users.
     *
     * Returning null is what the port already specifies for this case ("null when the caller could
     * not resolve one; such a row remains reachable by `customerId`"), and it makes the gap
     * countable rather than merely absent — see `erasure_identity_total{source="absent"}`.
     *
     * Deliberately NOT a refusal: rejecting the chat turn would not erase one extra row, and would
     * take a working assistant away from the majority of real users over a Keycloak attribute they
     * cannot set themselves. The reach is identical either way; only the honesty of the record
     * changes. Seeding the missing `party_id` attribute upstream remains the real fix (#4156).
     */
    private fun erasureIdentity(): String? {
        val jwt = identity.principal as? JsonWebToken
        val partyId = jwt?.getClaim<String>("party_id")?.takeIf { it.isNotBlank() }
        metrics.recordErasureIdentity(
            if (partyId != null) CopilotMetricsAdapter.SOURCE_CLAIM else CopilotMetricsAdapter.SOURCE_ABSENT,
        )
        return partyId
    }

    /**
     * Streaming chat (SSE). Identical governance as [chat] — same guard, same tool loop, same policy
     * gate — but text tokens are forwarded to the client as Server-Sent Events as they arrive from
     * the model backend instead of buffering the full response first.
     *
     * Returns [Multi]<[String]> so RESTEasy Reactive controls the SSE lifecycle. Using the legacy
     * JAX-RS `SseEventSink` + `@Blocking` caused a race: `SseResponseWriterHandler` ran after the
     * blocking method closed the sink, corrupting the Vert.x HTTP response and aborting the TCP
     * connection before the client received any events.
     *
     * [Infrastructure.getDefaultWorkerPool] carries the CDI request context (SmallRye Context
     * Propagation), so [SecurityIdentity] and the propagated bearer for tool calls are available
     * inside the `runBlocking` body.
     */
    @POST
    @Path("/chat/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Authenticated
    @Authorize(action = "copilot.chat")
    fun chatStream(request: ChatRequest): Multi<String> {
        val customerId = customerSubject() ?: return Multi.createFrom().empty()
        val partyId = erasureIdentity()
        val turn = ChatTurn(
            conversationId = request.conversationId ?: "new",
            message = request.message,
            currentThemeSpec = request.themeSpec,
        )
        return Multi.createFrom().emitter<String> { emitter ->
            try {
                runBlocking {
                    chat.handleStream(turn, customerId, partyId) { chunk ->
                        emitter.emit(chunk)
                    }
                }
            } finally {
                emitter.complete()
            }
        }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
    }

    /** [themeSpec] = client's active ThemeSpec JSON (ADR-0190), data context for design_theme. */
    data class ChatRequest(val conversationId: String? = null, val message: String = "", val themeSpec: String? = null)
}
