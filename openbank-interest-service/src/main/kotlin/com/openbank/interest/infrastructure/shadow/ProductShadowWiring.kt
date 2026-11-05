// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.shadow

import com.openbank.interest.application.port.out.CatalogRevisionIrPort
import com.openbank.interest.application.port.out.ProductShadowQueuePort
import com.openbank.interest.application.port.out.RevisionIrCopyPort
import com.openbank.interest.application.port.out.ShadowLegacySourcePort
import com.openbank.interest.application.usecase.InterestShadowComparator
import com.openbank.interest.application.usecase.ProductShadowService
import com.openbank.interest.application.usecase.ProductShadowSources
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.OpenBaoTransitIrSigner
import com.openbank.libs.productrevision.shadow.ProductShadowRecorder
import com.openbank.libs.security.CachingOpenBaoTokenSource
import com.openbank.libs.security.OpenBaoKubernetesLogin
import com.openbank.libs.security.OpenBaoTransport
import io.micrometer.core.instrument.MeterRegistry
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
 * How interest verifies a product revision's publish-path signature before its IR may be compared
 * (ADR-0352 §4), the same three modes billing uses:
 *
 * - [DISABLED] (default): nothing is verified and nothing is compared; every IR-bearing input is
 *   counted `not_comparable{reason="verification_disabled"}`.
 * - [OPENBAO]: OpenBao Transit `verify` on the publish key; interest's role would hold `verify` only.
 * - [HMAC]: the dev/test secret product-catalog signs with in those profiles; refused at boot in a
 *   production launch.
 */
object IrVerificationMode {
    const val DISABLED = "disabled"
    const val OPENBAO = "openbao"
    const val HMAC = "hmac"
    const val PROPERTY = "openbank.interest.product-shadow.verification.mode"
    private val ALL = listOf(DISABLED, OPENBAO, HMAC)

    fun parse(value: String): String =
        value.takeIf { it in ALL } ?: error("$PROPERTY must be one of $ALL, was '$value'")

    fun permitted(mode: String, launchMode: LaunchMode): Boolean =
        mode != HMAC || launchMode == LaunchMode.DEVELOPMENT || launchMode == LaunchMode.TEST
}

/** Wires the shadow comparator. libs-runtime registers no beans (ADR-0320 rule 1); the producers live here. */
@ApplicationScoped
class ProductShadowWiring(
    @ConfigProperty(name = IrVerificationMode.PROPERTY, defaultValue = IrVerificationMode.DISABLED)
    private val mode: String,
    @ConfigProperty(name = "openbank.interest.product-shadow.verification.key-id", defaultValue = "product-ir-publish")
    private val keyId: String,
    @ConfigProperty(name = "openbank.interest.product-shadow.verification.hmac-secret")
    private val hmacSecret: Optional<String>,
    @ConfigProperty(
        name = "openbank.interest.product-shadow.verification.openbao.address",
        defaultValue = "https://openbao.vault.svc:8200",
    )
    private val baoAddress: String,
    @ConfigProperty(
        name = "openbank.interest.product-shadow.verification.openbao.role",
        defaultValue = "interest-ir-verify",
    )
    private val role: String,
    @ConfigProperty(
        name = "openbank.interest.product-shadow.verification.openbao.sa-token-path",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val saTokenPath: String,
) {
    /** Null when verification is disabled: the service then compares nothing and says so. */
    fun verifier(): IrSignatureVerifierPort? = when (IrVerificationMode.parse(mode)) {
        IrVerificationMode.DISABLED -> null
        IrVerificationMode.OPENBAO -> {
            val transport = OpenBaoTransport(URI.create(baoAddress))
            OpenBaoTransitIrSigner(
                transport,
                keyId,
                CachingOpenBaoTokenSource(OpenBaoKubernetesLogin(transport, role, Path.of(saTokenPath))::login),
            )
        }
        else -> HmacIrSigner(
            hmacSecret.orElseThrow {
                IllegalStateException("${IrVerificationMode.PROPERTY}=hmac needs a verification.hmac-secret")
            }.toByteArray(Charsets.UTF_8),
            keyId,
        )
    }

    @Produces
    @Singleton
    fun productShadowService(
        queue: ProductShadowQueuePort,
        legacy: ShadowLegacySourcePort,
        copies: RevisionIrCopyPort,
        catalog: CatalogRevisionIrPort,
        meters: MeterRegistry,
    ): ProductShadowService = ProductShadowService(
        sources = ProductShadowSources(queue, legacy, copies, catalog),
        verifier = verifier(),
        comparator = InterestShadowComparator(ProductInterpreter(FinanceProductArithmetic)),
        recorder = ProductShadowRecorder(meters, CONSUMER),
        meters = meters,
    )

    companion object {
        const val CONSUMER = "interest"
    }
}

/**
 * Which path DECIDES an accrual or capitalisation (ADR-0352 §9: shadow, then switch). `shadow`
 * (default, and the only value accepted today): `InterestService` decides and posts, the product IR
 * is only compared. `ir` is reserved for the switch and refused at boot until it is built, which
 * ADR-0352 §9/§10 allows only after zero divergence across a full monthly cadence, at the scale-6
 * accrual and at the currency-scale capitalisation.
 */
object ProductIrDecisionMode {
    const val PROPERTY = "openbank.interest.product-ir.decision-mode"
    const val SHADOW = "shadow"
}

/**
 * Boot-time validation (`@Startup`: an `@ApplicationScoped` bean's `init` would otherwise run on first
 * use): an unknown verification mode, HMAC in a production launch, or any decision mode other than
 * `shadow` fails the boot instead of reaching a scheduler.
 */
@Startup
@ApplicationScoped
class ProductIrModeGuard(
    @ConfigProperty(name = IrVerificationMode.PROPERTY, defaultValue = IrVerificationMode.DISABLED)
    verification: String,
    @ConfigProperty(name = ProductIrDecisionMode.PROPERTY, defaultValue = ProductIrDecisionMode.SHADOW)
    decision: String,
) {
    init {
        val parsed = IrVerificationMode.parse(verification)
        check(IrVerificationMode.permitted(parsed, LaunchMode.current())) {
            "${IrVerificationMode.PROPERTY}=hmac is refused in launch mode ${LaunchMode.current()} (ADR-0352 §4)"
        }
        check(decision == ProductIrDecisionMode.SHADOW) {
            "${ProductIrDecisionMode.PROPERTY}=$decision is not available: interest decides accruals and " +
                "capitalisations on the legacy path until the product-IR shadow shows zero divergence across " +
                "a full cadence (ADR-0352 §9)"
        }
    }
}
