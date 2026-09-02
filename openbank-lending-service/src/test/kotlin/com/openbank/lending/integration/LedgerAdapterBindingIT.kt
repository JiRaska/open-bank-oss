// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.infrastructure.adapter.RestLedgerPostingAdapter
import com.openbank.lending.infrastructure.client.AccountServiceClient
import com.openbank.lending.infrastructure.client.BorrowerCreditClient
import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.arc.Arc
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves the `@IfBuildProperty` gates really deliver the REST adapters under the values the shipped
 * image carries, and — in [InertLedgerAdapterBindingIT] below — really withhold them otherwise
 * (#6057).
 *
 * ## Why this must be a `@QuarkusTest` with a `@TestProfile`
 *
 * The gates are resolved during augmentation, and ArC then removes the losing bean's class from the
 * application outright. No unit test can observe that: one which mocks `LedgerPostingPort` is blind
 * to which implementation CDI would have chosen, which is how the defect survived a service with an
 * 80% line-coverage floor. A `QuarkusTestProfile`'s config overrides force a *fresh augmentation*
 * with those values, so this exercises the real mechanism rather than a description of it.
 *
 * ## Why it asks ArC, not an `@Inject`ed field
 *
 * `TestRecordingLedgerPostingPort` and the `TestBorrowerCreditStubs` are `@Priority(200)`
 * alternatives, so an injected port resolves to *them*, not to the adapter under test — an
 * assertion on an injected field would be about the test's own scaffolding. Asking the container
 * whether the adapter bean exists at all is the question the build-time gate actually answers.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(RestBackendsProfile::class)
class LedgerAdapterBindingIT {

    @Test
    fun `the real REST ledger adapter is present in the application`() {
        assertThat(Arc.container().instance(RestLedgerPostingAdapter::class.java).isAvailable)
            .describedAs(
                "lending.ledger.backend=rest must make RestLedgerPostingAdapter a bean. Absent, " +
                    "the @Default NoOpLedgerPostingPort is what CDI binds — #6057, every " +
                    "general-ledger posting discarded by an adapter that reported success.",
            )
            .isTrue()
    }

    @Test
    fun `the real borrower credit and account lookup clients are present in the application`() {
        assertThat(Arc.container().instance(BorrowerCreditClient::class.java).isAvailable)
            .describedAs("lending.borrower-credit.backend=rest must make BorrowerCreditClient a bean")
            .isTrue()
        assertThat(Arc.container().instance(AccountServiceClient::class.java).isAvailable)
            .describedAs("lending.borrower-credit.backend=rest must make AccountServiceClient a bean")
            .isTrue()
    }
}

/**
 * The other half of the same claim: with the offline values, the real adapters are genuinely absent.
 *
 * Without this, the test above could pass for a reason unrelated to the gate — an adapter that was
 * never gated at all would satisfy it just as well. Together the two profiles show the property is
 * what decides.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OfflineBackendsProfile::class)
class InertLedgerAdapterBindingIT {

    @Test
    fun `the real adapters are absent when the backends are not selected`() {
        assertThat(Arc.container().instance(RestLedgerPostingAdapter::class.java).isAvailable).isFalse()
        assertThat(Arc.container().instance(BorrowerCreditClient::class.java).isAvailable).isFalse()
        assertThat(Arc.container().instance(AccountServiceClient::class.java).isAvailable).isFalse()
    }
}

/**
 * The adapter selection the packaged image carries (`%prod` in `application.yaml`).
 *
 * Values are literals on purpose: a `QuarkusTestProfile` loads in a different classloader from the
 * test class, so anything derived in `getConfigOverrides()` is computed twice and the two copies
 * need not agree.
 */
class RestBackendsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "lending.ledger.backend" to "rest",
        "lending.borrower-credit.backend" to "rest",
        "lending.outbox.backend" to "jpa",
    )
}

/** The offline selection ADR-0028 D3 protects: no real outbound dependency in the application. */
class OfflineBackendsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "lending.ledger.backend" to "none",
        "lending.borrower-credit.backend" to "none",
        "lending.outbox.backend" to "jpa",
    )
}
