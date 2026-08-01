// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * AC11 (issue #2990): account-service must never call delegation-service
 * synchronously on the request path — enforcement reads the local projection.
 * This test is the tripwire against someone "temporarily" adding a REST client.
 */
class NoDelegationRestClientTest {

    @Test
    fun `no REST client in this service targets delegation-service`() {
        val clientDir = File("src/main/kotlin/com/openbank/account/infrastructure/client")
        val offenders = clientDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("delegation-service") ||
                    text.contains("""configKey = "delegation""")
            }
            .map { it.name }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
