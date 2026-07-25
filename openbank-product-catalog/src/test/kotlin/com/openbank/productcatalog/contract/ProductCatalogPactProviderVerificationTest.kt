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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side Pact verification for the contracts consumers publish against
 * `openbank-product-catalog` (ADR-0063 git-pact: the pact JSON is read from the repo-root `pacts/`
 * dir via [PactFolder], no Pact Broker and no CI secret involved, so this always runs — locally and
 * on every path-scoped CI build of this module).
 *
 * Today that is one consumer: `openbank-card-issuance-service`'s card-entitlement lookup
 * (`ProductCatalogPactConsumerTest`). Each interaction is replayed against a real Quarkus instance
 * on a Testcontainers Postgres, so what is proven is the live HTTP + JSON shape, not a hand-written
 * restatement of it.
 *
 * Neither provider state needs to seed anything, and that is a property of this service rather than
 * a shortcut — see the [State] methods.
 *
 * Auth: `%test` disables OIDC (there is no Keycloak on the test stack) and keeps `@Authorize`
 * advisory, and [TestSecurity] supplies the operator identity every real caller carries. The
 * consumer pact therefore pins no `Authorization` header; the "reads require a token" half of the
 * contract is owned by `ProductCatalogSecurityTest`.
 *
 * IMPORTANT: if the consumer changes its contract, regenerate the pact
 * (`./gradlew :openbank-card-issuance-service:test --tests "*ProductCatalogPactConsumerTest*"`) and
 * commit `pacts/openbank-card-issuance-service-openbank-product-catalog.json` in the same PR, or
 * this test verifies a stale contract. `pact-drift-check.yml` gates that.
 *
 * [IgnoreNoPactsToVerify] makes a missing/unreadable pact folder a skip rather than a failure —
 * relevant only if `pacts/` is ever emptied ahead of a broker migration.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-product-catalog")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class ProductCatalogPactProviderVerificationTest {

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
}
