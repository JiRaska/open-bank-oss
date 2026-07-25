// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.ModelRequest
import com.openbank.agent.domain.model.ModelResponse

/**
 * Outbound port for a model backend (ADR-0031 D6). One implementation per *kind* of backend
 * (mock, a hosted public API, a self-hosted vLLM endpoint, …). `ModelGateway` selects the
 * provider whose [key] matches a [ModelDescriptor.provider], so the rest of the system
 * never depends on a concrete vendor.
 *
 * Implementations must be side-effect-free beyond the network call and must NOT log raw
 * prompt content (PII): the gateway owns audit and prompt hashing.
 *
 * This port is the Apache/AGPL seam (ADR-0031 D8): the Apache-2.0 monorepo keeps only governance plumbing
 * and reference/mock adapters; the commercialised agent runtime and proprietary model adapters
 * live in the separate AGPL-3.0 repository and plug in here. Keep production-runtime logic out of
 * this module.
 */
interface ModelProvider {

    /** Stable provider key matched against `model-gateway.models[*].provider`, e.g. "mock". */
    val key: String

    suspend fun complete(model: ModelDescriptor, request: ModelRequest): ModelResponse
}
