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
import com.openbank.productcatalog.domain.ProductIds
import com.openbank.productcatalog.domain.ProductSeed
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import org.hamcrest.Matchers.equalTo
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
 * Both classes replay mutating Product Studio interactions with pact-pinned UUIDs. They must
 * have separate test databases: the first replay publishes an immutable revision, so the second
 * cannot reset that provider state by deleting it. `restrictToAnnotatedClass` restarts the
 * Quarkus test resource between the folder and broker verifiers.
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
    restrictToAnnotatedClass = true,
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR", "CATALOG_SCOPE_READ"])
@Provider("openbank-product-catalog")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class ProductCatalogPactBrokerProviderVerificationTest {

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

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
        // The pension consumer is a scoped machine caller, unlike the default Pact operator.
        // Mirror the always-on provider verifier and restore identity after every interaction.
        val originalIdentity = testIdentityAssociation.testIdentity
        try {
            if (context?.consumer?.name == "openbank-pension-service") {
                testIdentityAssociation.setTestIdentity(pensionIdentity())
            }
            context?.verifyInteraction()
        } finally {
            testIdentityAssociation.setTestIdentity(originalIdentity)
        }
    }

    private fun pensionIdentity(): QuarkusSecurityIdentity {
        val token = mockk<JsonWebToken>()
        every { token.name } returns "service-account-openbank-pension"
        every { token.subject } returns "pact-pension-service"
        every { token.getClaim<String>("azp") } returns "openbank-pension"
        every { token.getClaim<String>("preferred_username") } returns "service-account-openbank-pension"
        return QuarkusSecurityIdentity.builder()
            .setPrincipal(token)
            .addRole("ROLE_API")
            .addRole("CATALOG_SCOPE_READ")
            .build()
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
    fun unknownProductCodeNotFound() {
        // Assert the negative provider state before Pact replays its own 404 interaction.
        io.restassured.RestAssured.given().get("/api/v1/products/by-code/NO_SUCH_CARD_PRODUCT")
            .then().statusCode(404)
    }

    /** Assert the real seeded schedule; never replace it with consumer-shaped fixture data. */
    @State("a product with fees exists for code CURRENT_PERSONAL")
    fun feeBearingProductIsSeeded() {
        val product = ProductSeed.products.single { it.code == "CURRENT_PERSONAL" }
        val monthlyFee = product.fees.single { it.name == "Monthly Fee" }
        val id = ProductIds.canonicalId(product.id)
        given().accept("application/json").get("/api/v1/products/$id/fees")
            .then().statusCode(200)
            .body("find { it.name == 'Monthly Fee' }.type", equalTo(monthlyFee.type))
            .body("find { it.name == 'Monthly Fee' }.amount", equalTo(monthlyFee.amount.toFloat()))
            .body("find { it.name == 'Monthly Fee' }.currency", equalTo(monthlyFee.currency))
            .body("find { it.name == 'Monthly Fee' }.waivable", equalTo(monthlyFee.waivable))
    }

    @State("a published v1-projected deposit revision exists for billing")
    fun publishedLegacyDepositRevisionExists() = catalogFixtures.publishedLegacyDepositRevisionExists()

    /**
     * The pact-pinned UUID is deliberately absent from `ProductSeed`; the seeded catalogue
     * already satisfies the account consumer's negative lookup state.
     */
    @State("no product exists with id 00000000-0000-0000-0000-000000000fff")
    fun unknownProductIdNotFound() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * Keep-in-sync with the folder twin: `ProductCatalogSeeder` persists the whole canonical
     * catalogue from `ProductSeed` on first boot, so the assistant consumer's `list_products`
     * state is satisfied by every boot. The list is a global array — the pact pins per-row
     * shape, never count or order.
     */
    @State("the canonical catalog products are seeded")
    fun canonicalCatalogProductsAreSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * Keep-in-sync with the folder twin: `prod-001` is `SAVINGS_STANDARD` in `ProductSeed` on
     * every boot — ACTIVE, with two seeded fees (the assistant consumer's `get_product` /
     * `get_product_fees` states).
     */
    @State("a product exists with id prod-001")
    fun pactProductIsSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * Keep-in-sync with the folder twin: the pact-pinned UUID is deliberately absent from
     * `ProductSeed`; the seeded catalogue already satisfies the assistant consumer's negative
     * `get_product` state.
     */
    @State("no product exists with id 00000000-0000-0000-0000-000000000eee")
    fun agentUnknownProductIdNotFound() {
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

    @State("a published DIP strategy revision has separate legal and product approvals")
    fun publishedPensionStrategyApprovalsExist() = catalogFixtures.publishedPensionStrategyApprovalsExist()
}
