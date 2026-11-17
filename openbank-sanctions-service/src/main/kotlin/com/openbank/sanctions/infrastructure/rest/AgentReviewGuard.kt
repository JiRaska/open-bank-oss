// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.authz.ServiceAccountIdentity
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.ext.Provider
import jakarta.ws.rs.ext.ReaderInterceptor
import jakarta.ws.rs.ext.ReaderInterceptorContext
import org.eclipse.microprofile.config.ConfigProvider

/**
 * The agent identity's review guard (#16381), applied while the request body is READ, which is
 * before the `@Authorize` interceptor runs: a refused agent request never reaches the four-eyes
 * gate, so it never creates a pending approval a human would be asked to decide.
 *
 * For `service-account-openbank-agent` on `POST /api/v3/sanctions/review` it refuses (403):
 * - any `newStatus` other than `CLEAR` (owner decision, #16455);
 * - every request while `authz.four-eyes.enforce` is not `true` — the agent is admitted only as a
 *   four-eyes MAKER, so with the gate off its review would execute unapproved. This makes the agent
 *   path fail CLOSED regardless of the deployed value, whose code default is `false`.
 * Every other caller passes through untouched.
 */
@Provider
class AgentReviewGuard : ReaderInterceptor {

    @Context
    lateinit var resourceInfo: ResourceInfo

    @Context
    lateinit var securityContext: SecurityContext

    override fun aroundReadFrom(context: ReaderInterceptorContext): Any? {
        val value = context.proceed()
        val request = value as? ReviewRequest ?: return value
        val method = runCatching { resourceInfo.resourceMethod }.getOrNull() ?: return value
        if (method.declaringClass != SanctionsResourceV3::class.java) return value
        val principal = runCatching { securityContext.userPrincipal }.getOrNull()
        if (ServiceAccountIdentity.verifiedClientId(principal) != AGENT_CLIENT_ID) return value
        refusal(request.newStatus.name, fourEyesEnforced())?.let { throw ForbiddenException(it) }
        return value
    }

    companion object {
        const val AGENT_CLIENT_ID = "openbank-agent"
        val AGENT_REVIEW_TARGETS = setOf("CLEAR")
        private const val FOUR_EYES_KEY = "authz.four-eyes.enforce"

        /** Why an agent review is refused, or `null` when it may proceed to the four-eyes gate. */
        fun refusal(newStatus: String, fourEyesEnforced: Boolean): String? = when {
            !fourEyesEnforced -> "the agent identity may review only while four-eyes is enforced"
            newStatus !in AGENT_REVIEW_TARGETS -> "the agent identity may only review towards CLEAR"
            else -> null
        }

        private fun fourEyesEnforced(): Boolean = ConfigProvider.getConfig()
            .getOptionalValue(FOUR_EYES_KEY, String::class.java)
            .map { it.trim().equals("true", ignoreCase = true) }
            .orElse(false)
    }
}
