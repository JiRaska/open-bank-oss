// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Every `@RegisterRestClient(configKey = "x")` must have `quarkus.rest-client.x.url` configured.
 *
 * ## Why this exists
 *
 * This service declared its client URLs as BARE TOP-LEVEL keys — `consent-service.url`,
 * `incentive-service.url`. Quarkus does not read those for a REST client; it reads only
 * `quarkus.rest-client.<configKey>.url`. So every client was built with no base URL and threw from
 * `RestClientCDIDelegateBuilder.configureBaseUrl` the first time it was used.
 *
 * Both call sites catch broadly and fail closed, which is right — and which is exactly why nothing
 * went red. The failure surfaced in production as `credit consent unreadable for party …; treating
 * as absent` for EVERY party, and an enrolment sweep answering `{"enrolled":0}`: indistinguishable
 * from an empty segment unless you read the logs.
 *
 * No existing test could have caught it. They all inject the ports directly, so the Quarkus
 * configuration is never exercised — the seam that broke is the one the tests replace. This test
 * therefore checks the CONFIGURATION rather than the behaviour, because that is where the defect
 * lives.
 */
class RestClientUrlConfiguredTest {

    /** Gradle runs tests with the module as the working directory; a root-level run needs the suffix. */
    private val moduleRoot = File("").absoluteFile.let { cwd ->
        if (cwd.name == MODULE) cwd else File(cwd, MODULE)
    }

    @Test
    fun `every RegisterRestClient configKey has a quarkus rest-client url`() {
        val configKeys = File(moduleRoot, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { CONFIG_KEY.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSortedSet()

        // Guard the guard: if the scan finds nothing, the assertion below passes vacuously and this
        // test would report success while checking no clients at all.
        assertThat(configKeys)
            .describedAs("no @RegisterRestClient(configKey=...) found — the scan is broken, not the config")
            .isNotEmpty()

        @Suppress("UNCHECKED_CAST")
        val yaml = Yaml().load<Map<String, Any?>>(File(moduleRoot, "src/main/resources/application.yaml").readText())
        val restClient = ((yaml["quarkus"] as? Map<String, Any?>)?.get("rest-client") as? Map<String, Any?>).orEmpty()

        val missing = configKeys.filter { key ->
            val entry = restClient[key] as? Map<*, *>
            entry?.get("url")?.toString().isNullOrBlank()
        }

        assertThat(missing)
            .describedAs(
                "these REST clients have no `quarkus.rest-client.<key>.url`, so they build with no base " +
                    "URL and throw on first use — a bare top-level `<key>.url` does NOT count",
            )
            .isEmpty()
    }

    private companion object {
        const val MODULE = "openbank-campaign-service"
        val CONFIG_KEY = Regex("""@RegisterRestClient\s*\(\s*configKey\s*=\s*"([^"]+)"""")
    }
}
