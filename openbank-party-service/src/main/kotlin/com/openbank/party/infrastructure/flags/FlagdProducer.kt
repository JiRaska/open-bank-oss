// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.flags

import com.openbank.libs.flags.FeatureClient
import com.openbank.libs.flags.FlagdProvider
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * Wires the production [FeatureClient] for party-service — the first pilot adopter
 * of ADR-0067. Deliberately mirrors [com.openbank.party.infrastructure.authz.AuthzProducer]:
 * all knobs come from `application.yaml` so the same image runs locally (no flagd
 * sidecar → [FlagdProvider] fails static and every flag takes its default) and in
 * cluster (real flagd sidecar at `localhost:8013`).
 *
 * Test profiles can swap in `StaticFeatureClient` via a `@Produces` `@Alternative`
 * without touching this class.
 *
 * `@Startup` is load-bearing: the first caller is `enabledSuspending`, which runs on a
 * `Dispatchers.IO` worker whose context class loader has no SmallRye config registered. Created
 * lazily there, the `@ConfigProperty` injection failed with `SRCFG00015` and every
 * `GET /api/v1/parties` / `/search` answered 500. Creating the bean at boot resolves the config on
 * the main thread, and the producer method then only reads fields.
 */
@Startup
@ApplicationScoped
class FlagdProducer {
    @ConfigProperty(name = "openbank.flags.url", defaultValue = "http://localhost:8013")
    lateinit var flagsUrl: String

    @ConfigProperty(name = "openbank.flags.timeout-ms", defaultValue = "100")
    var flagsTimeoutMs: Long = 100

    @Produces
    @ApplicationScoped
    fun featureClient(): FeatureClient = FlagdProvider(
        baseUrl = flagsUrl,
        timeout = Duration.ofMillis(flagsTimeoutMs),
    )
}
