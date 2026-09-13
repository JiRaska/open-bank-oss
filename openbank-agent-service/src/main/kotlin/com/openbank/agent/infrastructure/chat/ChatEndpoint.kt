// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.chat

import com.openbank.agent.application.AgentChatService
import com.openbank.agent.application.ModelGateway
import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

/**
 * Server-side chat endpoint for the admin-UI assistant. The model call NEVER happens in the
 * browser — the UI posts here, this runs the governed reasoning loop ([AgentChatService]), and
 * returns the assistant reply plus a transparent record of which tools were called and whether
 * policy allowed them.
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE") // ADR-0031 D3: authenticated operator
@ApplicationScoped
class ChatEndpoint {

    @Inject lateinit var chatService: AgentChatService

    @Inject lateinit var gateway: ModelGateway

    data class ChatTurn(val role: String = "user", val content: String = "")
    data class ChatRequest(
        /**
         * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
         * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS
         * of a collection, so `{"messages": [null]}` deserialises happily into a `List<ChatTurn>`
         * holding a null. Writing the type honestly is what makes [requireMessages] reachable
         * instead of dead code.
         */
        val messages: List<ChatTurn?> = emptyList(),
        val model: String? = null,
        val context: String? = null,
    ) {
        /**
         * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`;
         * no service-local mapper is added (#526).
         */
        fun requireMessages(): List<ChatTurn> = messages.mapIndexed { index, turn ->
            requireNotNull(turn) { "messages[$index] must not be null" }
        }
    }

    data class ModelInfo(val id: String, val provider: String, val sensitivity: String)
    data class ChatResponse(
        val reply: String,
        val model: String,
        val toolCalls: List<AgentChatService.ToolCallRecord>,
        /** D4: true when the reply contains a recommended action requiring human confirmation. */
        val isProposal: Boolean = false,
    )

    @GET
    @Path("/models")
    fun models(): Map<String, Any> = mapOf(
        "default" to gateway.defaultModelId(),
        "models" to gateway.availableModels().map { ModelInfo(it.id, it.provider, it.sensitivity.name) },
    )

    // Plain (non-suspend) resource method: RESTEasy Reactive dispatches it on a worker thread, so the
    // governed loop's blocking clients (OPA PDP, MCP tool REST clients) are allowed. A `suspend` method
    // would run on the Vert.x event loop, where those calls throw BlockingNotAllowedException and the
    // policy gate fails closed; `suspend` + @Blocking is rejected outright by RESTEasy Reactive. We
    // bridge to the suspending service with runBlocking, which keeps CDI/audit context on this thread
    // (unlike a raw Dispatchers.IO switch).
    @POST
    @Path("/chat")
    fun chat(request: ChatRequest): ChatResponse = runBlocking {
        val history = request.requireMessages().map { ChatMessage(role = parseRole(it.role), content = it.content) }
        val outcome = chatService.chat(history = history, modelId = request.model, pageContext = request.context)
        ChatResponse(
            reply = outcome.reply,
            model = outcome.model,
            toolCalls = outcome.toolCalls,
            isProposal = outcome.isProposal,
        )
    }

    private fun parseRole(raw: String): ChatRole = when (raw.trim().lowercase()) {
        "system" -> ChatRole.SYSTEM
        "assistant" -> ChatRole.ASSISTANT
        "tool" -> ChatRole.TOOL
        else -> ChatRole.USER
    }
}
