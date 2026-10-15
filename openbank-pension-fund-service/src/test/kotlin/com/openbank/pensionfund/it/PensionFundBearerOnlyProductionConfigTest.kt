// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.readText

/** The production mTLS listener must require a bearer identity, including for unannotated API routes. */
class PensionFundBearerOnlyProductionConfigTest {
    @Test
    fun `production API permission requires an authenticated bearer`() {
        val config = Yaml().load<Map<String, Any>>(Path.of("src/main/resources/application.yaml").readText())
        val http = config.child("%prod").child("quarkus").child("http")
        val permission = http.child("auth").child("permission").child("bearer-only")

        assertThat(http.child("ssl")["client-auth"]).isEqualTo("required")
        assertThat(permission["paths"]).isEqualTo("/api/*")
        assertThat(permission["auth-mechanism"]).isEqualTo("bearer")
        assertThat(permission["policy"]).isEqualTo("authenticated")
    }
}

private fun Map<*, *>.child(key: String): Map<*, *> = requireNotNull(this[key] as? Map<*, *>) {
    "missing production config key $key"
}
