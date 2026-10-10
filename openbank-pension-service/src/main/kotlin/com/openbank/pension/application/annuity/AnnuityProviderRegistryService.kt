// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.annuity

import com.openbank.pension.domain.annuity.AnnuityProvider
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.domain.annuity.AnnuityProviderTerms
import java.net.URI
import java.time.Clock

/**
 * Operator CRUD of the partner registry (#12383) with FOUR-EYES activation: the aggregate refuses
 * an approver who edited or requested; this service adds the BUILD checks an aggregate cannot know
 * — the adapter kind must exist in this build, and a reference-protocol endpoint must be HTTPS
 * unless the environment explicitly allows otherwise (dev/test). A partner is asked to quote only
 * once ACTIVE.
 */
class AnnuityProviderRegistryService(
    private val providers: AnnuityProviderRepository,
    private val adapters: AnnuityAdapterCatalog,
    private val clock: Clock,
    private val allowInsecureEndpoints: Boolean,
) {
    suspend fun list(status: AnnuityProviderStatus?): List<AnnuityProvider> = providers.list(status)

    suspend fun get(partnerId: String): AnnuityProvider =
        providers.find(partnerId) ?: throw AnnuityNotFoundException("annuity partner $partnerId not found")

    suspend fun create(partnerId: String, terms: AnnuityProviderTerms, by: String): AnnuityProvider {
        check(providers.find(partnerId) == null) { "annuity partner $partnerId already exists" }
        validateEndpoint(terms)
        return providers.save(AnnuityProvider.draft(partnerId, terms, by, clock.instant()))
    }

    suspend fun amend(partnerId: String, terms: AnnuityProviderTerms, by: String): AnnuityProvider {
        validateEndpoint(terms)
        // A PROPOSAL: the approved version stays live until a different person approves this one.
        return providers.save(get(partnerId).propose(terms, by, clock.instant()))
    }

    suspend fun requestActivation(partnerId: String, by: String): AnnuityProvider {
        val provider = get(partnerId)
        requireAdapter(checkNotNull(provider.proposal) { "no proposed terms to approve" }.terms)
        return providers.save(provider.requestActivation(by, clock.instant()))
    }

    suspend fun approveActivation(partnerId: String, by: String): AnnuityProvider {
        val provider = get(partnerId)
        val proposed = checkNotNull(provider.proposal) { "no proposal is pending" }.terms
        requireAdapter(proposed)
        validateEndpoint(proposed)
        return providers.save(provider.approveActivation(by, clock.instant()))
    }

    suspend fun disable(partnerId: String): AnnuityProvider = providers.save(get(partnerId).disable(clock.instant()))

    private fun requireAdapter(terms: AnnuityProviderTerms) {
        check(adapters.adapterFor(terms.adapter) != null) {
            "adapter '${terms.adapter}' is not available in this build (available: ${adapters.kinds().sorted()})"
        }
    }

    /** SSRF guard: the operator-supplied endpoint is an absolute URL, HTTPS outside dev/test, no credentials. */
    private fun validateEndpoint(terms: AnnuityProviderTerms) {
        val raw = terms.endpointUrl ?: return
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("endpointUrl is not a URL") }
        require(uri.isAbsolute && uri.host != null) { "endpointUrl must be an absolute URL with a host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "endpointUrl carries no credentials, query or fragment"
        }
        require(uri.scheme == "https" || (allowInsecureEndpoints && uri.scheme == "http")) {
            "endpointUrl must use https"
        }
    }
}
