// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.signing

import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignerPort
import com.openbank.libs.productrevision.IrSigningException
import com.openbank.libs.productrevision.OpenBaoTransitIrSigner
import com.openbank.libs.security.CachingOpenBaoTokenSource
import com.openbank.libs.security.OpenBaoKubernetesLogin
import com.openbank.libs.security.OpenBaoTransport
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.nio.file.Path
import java.util.Optional

/**
 * The publish path's signing mode (ADR-0352 §4), from `openbank.catalog.ir-signing.mode`.
 *
 * - [DISABLED] (the default): nothing is signed and `publish-ir` answers 409. A deployment that has
 *   not provisioned its Transit key is in this state, never in [HMAC].
 * - [OPENBAO]: Transit `sign` on a dedicated key; the only mode a deployment may run.
 * - [HMAC]: a shared secret, which proves nothing about WHO signed. Dev and test launch modes only;
 *   [IrSigningModeGuard] refuses to boot a production launch with it.
 */
enum class IrSigningMode(val configValue: String) {
    DISABLED("disabled"),
    OPENBAO("openbao"),
    HMAC("hmac"),
    ;

    companion object {
        const val PROPERTY = "openbank.catalog.ir-signing.mode"
        const val DEFAULT = "disabled"

        fun parse(value: String): IrSigningMode = entries.firstOrNull { it.configValue == value }
            ?: error("$PROPERTY must be one of ${entries.map { it.configValue }}, was '$value'")

        /**
         * Whether [mode] may run under [launchMode]. HMAC outside dev/test would publish signatures
         * anyone holding the config could forge, so it is refused rather than warned about.
         */
        fun permitted(mode: IrSigningMode, launchMode: LaunchMode): Boolean =
            mode != HMAC || launchMode == LaunchMode.DEVELOPMENT || launchMode == LaunchMode.TEST
    }
}

/**
 * Wires the publish path's [IrSignerPort]. libs-runtime registers no bean for this (ADR-0320
 * classpath-guard rule 1); the producer lives here. Mode validation happens at boot in
 * [IrSigningModeGuard]; this producer only builds what the validated mode names.
 */
@ApplicationScoped
class IrSignerProducer(
    @ConfigProperty(name = IrSigningMode.PROPERTY, defaultValue = IrSigningMode.DEFAULT)
    private val mode: String,
    @ConfigProperty(name = "openbank.catalog.ir-signing.key-id", defaultValue = "product-ir-publish")
    private val keyId: String,
    @ConfigProperty(name = "openbank.catalog.ir-signing.hmac-secret")
    private val hmacSecret: Optional<String>,
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
    fun irSigner(): IrSignerPort = when (IrSigningMode.parse(mode)) {
        IrSigningMode.DISABLED -> IrSignerPort {
            throw IrSigningException("IR signing is disabled (${IrSigningMode.PROPERTY}=disabled)")
        }
        IrSigningMode.OPENBAO -> {
            val transport = OpenBaoTransport(URI.create(baoAddress))
            val login = OpenBaoKubernetesLogin(transport, role, Path.of(saTokenPath))
            OpenBaoTransitIrSigner(transport, keyId, CachingOpenBaoTokenSource(login::login), transitMount)
        }
        IrSigningMode.HMAC -> HmacIrSigner(
            hmacSecret.orElseThrow {
                IllegalStateException("${IrSigningMode.PROPERTY}=hmac needs openbank.catalog.ir-signing.hmac-secret")
            }.toByteArray(Charsets.UTF_8),
            keyId,
        )
    }
}

/**
 * Boot-time (not first-use: an `@ApplicationScoped` bean's `init` runs on first use, which for
 * `publish-ir` may be never) validation of the signing mode. An unknown mode or HMAC under a
 * production launch fails the boot instead of reaching a request.
 */
@Startup
@ApplicationScoped
class IrSigningModeGuard(
    @ConfigProperty(name = IrSigningMode.PROPERTY, defaultValue = IrSigningMode.DEFAULT)
    mode: String,
) {
    init {
        val parsed = IrSigningMode.parse(mode)
        check(IrSigningMode.permitted(parsed, LaunchMode.current())) {
            "${IrSigningMode.PROPERTY}=hmac is refused in launch mode ${LaunchMode.current()}: a shared-secret " +
                "signature proves nothing about who signed (ADR-0352 §4). Use openbao, or disabled."
        }
    }
}
