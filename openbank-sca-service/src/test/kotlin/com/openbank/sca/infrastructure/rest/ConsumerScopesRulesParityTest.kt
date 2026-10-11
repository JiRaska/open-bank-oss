// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.sca.domain.model.ConsumerScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * ADR-0335 D1: rules.yaml `scoped_sca_consumers` is the single declaration. sca_rest_ext.rego reads
 * its `principal` + `actions`; this test holds the domain's [ConsumerScopes] to its `purposes` and
 * `approval_request_prefix`, so the policy layer and the domain layer cannot drift apart.
 */
class ConsumerScopesRulesParityTest {
    private val declared: List<Map<*, *>> = run {
        val rules = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "openbank-libs/governance/rules.yaml") }
            .first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        (Yaml().load<Map<String, Any>>(rules.readText())["scoped_sca_consumers"] as List<Map<*, *>>)
    }

    @Test
    fun `every declared scoped consumer has exactly the declared domain scope`() {
        assertThat(declared).isNotEmpty()
        declared.forEach { entry ->
            val scope = ConsumerScopes.forPrincipal(entry["principal"] as String)
            assertThat(scope).`as`(entry["principal"].toString()).isInstanceOf(ConsumerScope.Reserved::class.java)
            val namespace = (scope as ConsumerScope.Reserved).namespace
            assertThat(namespace.prefix).isEqualTo(entry["approval_request_prefix"])
            assertThat(
                namespace.purposes.map {
                    it.name
                },
            ).containsExactlyInAnyOrderElementsOf(entry["purposes"] as List<String>)
            assertThat(entry["actions"] as List<*>).containsExactly("scaChallenge.consume")
        }
    }

    @Test
    fun `the domain holds no reservation the rules do not declare`() {
        assertThat(ConsumerScopes.reservedPrincipals()).containsExactlyInAnyOrderElementsOf(
            declared.map {
                it["principal"] as String
            },
        )
    }
}
