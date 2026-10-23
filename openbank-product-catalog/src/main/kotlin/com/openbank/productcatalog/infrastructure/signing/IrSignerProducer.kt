// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.signing

import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignerPort
import com.openbank.libs.productrevision.OpenBaoTransitIrSigner
import com.openbank.libs.security.CachingOpenBaoTokenSource
import com.openbank.libs.security.OpenBaoKubernetesLogin
import com.openbank.libs.security.OpenBaoTransport
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.nio.file.Path

/**
 * Wires the publish path's [IrSignerPort] (ADR-0352 §4). Two modes, selected by
 * `openbank.catalog.ir-signing.mode`:
 *
 * - `openbao` — [OpenBaoTransitIrSigner] over Transit `sign` on a dedicated key, token from the
 *   pod's Kubernetes auth login. The ONLY mode acceptable for a deployment: whoever can produce a
 *   valid signature changes what every account under a product does, so the capability lives in
 *   OpenBao policy on this service's role and nowhere else.
 * - `hmac` (default, so a laptop and a `@QuarkusTest` boot) — [HmacIrSigner] over a shared secret.
 *   A shared secret cannot prove who signed; [IrSigningModeGuard] warns at BOOT (`@Startup`, because
 *   an `@ApplicationScoped` bean's `init` runs on first use, which for publish-ir may be never).
 *
 * libs-runtime registers no bean for this (ADR-0320 classpath-guard rule 1); the producer is here.
 */
@ApplicationScoped
class IrSignerProducer(
    @ConfigProperty(name = "openbank.catalog.ir-signing.mode", defaultValue = "hmac")
    private val mode: String,
    @ConfigProperty(name = "openbank.catalog.ir-signing.key-id", defaultValue = "product-ir-publish")
    private val keyId: String,
    @ConfigProperty(
        name = "openbank.catalog.ir-signing.hmac-secret",
        defaultValue = "CHANGE_ME_LOCAL_DEV_ONLY_32_BYTES",
    )
    private val hmacSecret: String,
    @ConfigProperty(
        name = "openbank.catalog.ir-signing.openbao.address",
        defaultValue = "https://openbao.vault.svc:8200",
    )
    private val baoAddress: String,
    @ConfigProperty(name = "openbank.catalog.ir-signing.openbao.transit-mount", defaultValue = "transit")
    private val transitMount: String,
    @ConfigProperty(name = "openbank.catalog.ir-signing.openbao.role", defaultValue = "product-catalog-ir-signing")
    private val role: String,
    @ConfigProperty(
        name = "openbank.catalog.ir-signing.openbao.sa-token-path",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val saTokenPath: String,
) {
    @Produces
    @Singleton
    fun irSigner(): IrSignerPort = when (mode) {
        MODE_OPENBAO -> {
            val transport = OpenBaoTransport(URI.create(baoAddress))
            val login = OpenBaoKubernetesLogin(transport, role, Path.of(saTokenPath))
            OpenBaoTransitIrSigner(transport, keyId, CachingOpenBaoTokenSource(login::login), transitMount)
        }
        MODE_HMAC -> HmacIrSigner(hmacSecret.toByteArray(Charsets.UTF_8), keyId)
        else -> error("openbank.catalog.ir-signing.mode must be '$MODE_OPENBAO' or '$MODE_HMAC', was '$mode'")
    }

    companion object {
        const val MODE_OPENBAO = "openbao"
        const val MODE_HMAC = "hmac"
    }
}

/** Boot-time (not first-use) warning that the publish path is signing with a dev/test adapter. */
@Startup
@ApplicationScoped
class IrSigningModeGuard(
    @ConfigProperty(name = "openbank.catalog.ir-signing.mode", defaultValue = "hmac")
    private val mode: String,
) {
    private val log = Logger.getLogger(IrSigningModeGuard::class.java)

    init {
        if (mode != IrSignerProducer.MODE_OPENBAO) {
            log.warnf(
                "openbank.catalog.ir-signing.mode=%s: product IR revisions are signed with a shared-secret HMAC, " +
                    "which proves nothing about WHO signed. DEV/TEST ONLY — set mode=openbao in any deployment " +
                    "(ADR-0352 §4).",
                mode,
            )
        }
    }
}
