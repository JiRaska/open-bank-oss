// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-side provider verification for openbank-product-catalog, the published-result counterpart to
 * [ProductCatalogPactProviderVerificationTest].
 *
 * WHY BOTH EXIST. A `@PactFolder` test replays the COMMITTED pact from disk: it proves this
 * provider still honours the contract, on every PR, with no infrastructure. It never contacts
 * the broker, so it publishes nothing — and `can-i-deploy` reads published verification
 * results, not green test runs. Without this class the broker never learned that
 * openbank-product-catalog verifies anything, so its consumers (openbank-card-issuance-service) stayed
 * permanently UNVERIFIED and could not be deployed (issue #3232).
 *
 * A second `@Provider("openbank-product-catalog")` class is safe here for the reason
 * CLAUDE.md gives for ledger-service's identical pair: the collision it warns about is HTTP vs
 * MESSAGE target dispatch fighting over the same `@BeforeEach`, and both classes here use the
 * same target type, so verifying the same interactions from two pact sources is at worst
 * redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, which have no broker
 * configured. It runs on the main push, where `_service-ci.yml` sets `PUBLISH_RESULTS=true`
 * — that is the run whose result `can-i-deploy` gates the deploy on. The `@PactFolder` class
 * keeps running unconditionally, so PR-time contract coverage is unchanged by this addition.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-product-catalog")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class ProductCatalogPactBrokerProviderVerificationTest {

    @Inject
    lateinit var catalogFixtures: CatalogPactProviderFixtures

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * No seeding: `ProductCatalogSeeder` persists the whole canonical catalogue from `ProductSeed`
     * on first boot, and `CURRENT_PERSONAL` (prod-003) is one of the card-enabled entries. Seeding
     * it again here would either lose the UNIQUE(code) race or fork a second source of truth for a
     * product the service already owns. If this state ever starts failing, the cause is a
     * `ProductSeed` edit that dropped `CURRENT_PERSONAL`'s `cardConfig` — which is exactly the
     * regression card issuance needs to hear about, so let it fail rather than seeding around it.
     */
    @State("a product with card configuration exists for code CURRENT_PERSONAL")
    fun cardEnabledProductIsSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * No seeding: `NO_SUCH_CARD_PRODUCT` is deliberately absent from `ProductSeed`, so the seeded
     * catalogue already satisfies "no product exists with this code".
     */
    @State("no product exists with code NO_SUCH_CARD_PRODUCT")
    fun unknownProductCodeIsAbsent() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * No seeding: `CURRENT_PERSONAL` carries four seeded fees in `ProductSeed` on every boot.
     * Keep this broker replay state in lock-step with the always-on `@PactFolder` verifier so a
     * newly published billing pact cannot pass on a PR and then strand at `can-i-deploy`.
     */
    @State("a product with fees exists for code CURRENT_PERSONAL")
    fun feeBearingProductIsSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * The pact-pinned UUID is deliberately absent from `ProductSeed`; the seeded catalogue
     * already satisfies the account consumer's negative lookup state.
     */
    @State("no product exists with id 00000000-0000-0000-0000-000000000fff")
    fun unknownProductIdIsAbsent() {
        // Intentionally empty — see the KDoc above.
    }

    @State("trusted insurance term-life schema version 1 is installed")
    fun trustedInsuranceSchemaIsInstalled() {
        // CatalogPackSeeder installs the trusted test pack before provider verification.
    }

    @State("Product Studio specification code is available")
    fun productStudioSpecificationCodeIsAvailable() = catalogFixtures.specificationCodeIsAvailable()

    @State("Product Studio offering prerequisite specification exists")
    fun productStudioOfferingPrerequisiteExists() = catalogFixtures.offeringPrerequisiteExists()

    @State("Product Studio draft prerequisite offering exists")
    fun productStudioDraftPrerequisiteExists() = catalogFixtures.draftPrerequisiteOfferingExists()

    @State("Product Studio editable draft exists")
    fun productStudioEditableDraftExists() = catalogFixtures.editableDraftExists()

    @State("Product Studio independently checkable draft exists")
    fun productStudioCheckableDraftExists() = catalogFixtures.independentlyCheckableDraftExists()

    @State("a published fixed-rate deposit revision exists for interest synchronization")
    fun publishedFixedRateDepositRevisionExists() = catalogFixtures.publishedFixedRateDepositRevisionExists()

    @State("a published priced loan revision exists for lending originations")
    fun publishedPricedLoanRevisionExists() = catalogFixtures.publishedPricedLoanRevisionExists()
}
