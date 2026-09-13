// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.openbank.copilot.domain.CreditAiLevel
import com.openbank.copilot.infrastructure.client.ConsentQueryClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Resolves the customer's ADR-0269 credit AI level from their consents.
 *
 * ## Why every failure resolves to L0 rather than to an error
 *
 * The levels are not a permission to READ something the customer owns; they are a permission for
 * the bank to look at the customer's finances and to prepare things on their behalf. When the
 * consent store cannot be reached, the safe answer is the level that does neither — and L0 is a
 * working assistant, not a refusal, so degrading to it costs the customer an explanation rather
 * than access to their bank.
 *
 * That is the opposite decision from lending's offer gate, which fails closed into a refusal, and
 * the difference is deliberate: there, the risk is offering credit to someone who did not consent;
 * here, the risk is reading a profile without consent. Both are closed by "assume the least".
 */
@ApplicationScoped
class CreditAiLevelResolver(@param:RestClient private val consents: ConsentQueryClient) {

    suspend fun levelFor(partyId: UUID): CreditAiLevel {
        val profileUse = granted(partyId, PROFILE_USE_SCOPE)
        val agent = granted(partyId, AGENT_SCOPE)
        return CreditAiLevel.from(profileUseConsent = profileUse, agentConsent = agent)
    }

    private suspend fun granted(partyId: UUID, scope: String): Boolean =
        runCatching { consents.hasActiveConsent(partyId, BANK_GRANTEE, scope).awaitSuspending().granted }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                LOG.warn("credit AI level: consent read failed for scope $scope — treating as absent")
                false
            }

    companion object {
        /** First-party consent: the bank is the grantee, not a TPP. */
        const val BANK_GRANTEE = "openbank"
        const val PROFILE_USE_SCOPE = "CREDIT_PROFILE_USE"
        const val AGENT_SCOPE = "CREDIT_AI_AGENT"
        private val LOG = Logger.getLogger(CreditAiLevelResolver::class.java)
    }
}
