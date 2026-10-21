// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * The /api/v1 compatibility adapter projects onto the banking pack, so a standalone boot that keeps
 * v1 enabled must also enable that pack. Lives on the v1 side of the boundary: the kernel does not
 * know which pack the v1 adapter needs (ADR-0257, #13429).
 */
@Startup
@ApplicationScoped
class BankV1PackGuard(
    @ConfigProperty(name = "openbank.catalog.require-https-issuer", defaultValue = "false")
    standaloneBoot: Boolean,
    @ConfigProperty(name = "openbank.catalog.bank-v1-compatibility-enabled", defaultValue = "true")
    bankCompatibilityEnabled: Boolean,
    @ConfigProperty(name = "openbank.catalog.packs") packs: Optional<String>,
) {
    init {
        if (standaloneBoot && bankCompatibilityEnabled) {
            require(BANKING_PACK in packs.orElse("").split(',').map(String::trim)) {
                "banking compatibility requires the trusted banking pack"
            }
        }
    }

    private companion object {
        const val BANKING_PACK = "banking"
    }
}
