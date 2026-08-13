// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.util.Optional

/** Refuses a production-style standalone boot whose OIDC discovery can cross plaintext HTTP. */
@Startup
@ApplicationScoped
class StandaloneIssuerGuard(
    @ConfigProperty(name = "openbank.catalog.require-https-issuer", defaultValue = "false")
    requireHttpsIssuer: Boolean,
    @ConfigProperty(name = "quarkus.oidc.auth-server-url") issuer: String,
    @ConfigProperty(name = "openbank.catalog.bank-v1-compatibility-enabled", defaultValue = "true")
    bankCompatibilityEnabled: Boolean,
    @ConfigProperty(name = "openbank.catalog.packs", defaultValue = "banking,insurance") packs: Optional<String>,
) {
    init {
        if (requireHttpsIssuer) {
            require(URI(issuer).scheme.equals("https", ignoreCase = true)) {
                "standalone OIDC issuer must use https"
            }
            require(!bankCompatibilityEnabled || "banking" in packs.orElse("").split(',').map(String::trim)) {
                "banking compatibility requires the trusted banking pack"
            }
        }
    }
}
