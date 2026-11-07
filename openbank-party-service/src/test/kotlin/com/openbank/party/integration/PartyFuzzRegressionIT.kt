// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.party.infrastructure.flags.FlagdProducer
import io.quarkus.arc.Arc
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * GET /api/v1/parties and /parties/search 500s found by the nightly API fuzz (run 41631915107).
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_fuzz_it")],
)
@TestProfile(PartyFuzzRegressionIT.FreshApplication::class)
class PartyFuzzRegressionIT {

    /**
     * A profile of its own makes Quarkus boot a FRESH application for this class, so no earlier
     * test in the shared JVM can have created [FlagdProducer] already. Without it the test could
     * pass vacuously: the bean would be handed back ready-made and the foreign-loader creation
     * path never exercised. With it, only `@Startup` can have created the bean before this runs.
     */
    class FreshApplication : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.fuzz-regression.profile" to "fresh")
    }

    /**
     * GET /api/v1/parties and /parties/search answered 500 `SRCFG00015: No configuration is
     * available for this class loader`: the lazily created [FlagdProducer] was first instantiated on
     * a `Dispatchers.IO` worker (`enabledSuspending` → `runInterruptible(Dispatchers.IO)`), whose
     * context class loader has no config registered, so its `@ConfigProperty` injection failed.
     * The test-scope `@Mock` FeatureClient hides that path from every HTTP test, so this creates
     * the bean from such a thread directly — the exact condition the endpoint hit in production.
     */
    @Test
    fun `FlagdProducer config resolves on a thread whose context class loader has no config`() {
        val failure = AtomicReference<Throwable?>()
        val url = AtomicReference<String?>()
        val t = Thread {
            Thread.currentThread().contextClassLoader = URLClassLoader(arrayOf(), null)
            try {
                url.set(Arc.container().instance(FlagdProducer::class.java).get().flagsUrl)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        t.start()
        t.join(JOIN_MS)
        assertThat(failure.get()).isNull()
        assertThat(url.get()).isNotBlank()
    }

    private companion object {
        const val JOIN_MS = 10_000L
    }
}
