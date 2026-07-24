// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.application.CopilotChatService
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
    lateinit var chat: CopilotChatService

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
        when (val outcome = chat.handle(turn, customerId)) {
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
        val turn = ChatTurn(
            conversationId = request.conversationId ?: "new",
            message = request.message,
            currentThemeSpec = request.themeSpec,
        )
        return Multi.createFrom().emitter<String> { emitter ->
            try {
                runBlocking {
                    chat.handleStream(turn, customerId) { chunk ->
                        emitter.emit(chunk)
                    }
                }
            } finally {
                emitter.complete()
            }
        }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
    }

    /** [themeSpec] = client's active ThemeSpec JSON (ADR-0190), data context for design_theme. */
    data class ChatRequest(
        val conversationId: String? = null,
        val message: String = "",
        val themeSpec: String? = null,
    )
}
