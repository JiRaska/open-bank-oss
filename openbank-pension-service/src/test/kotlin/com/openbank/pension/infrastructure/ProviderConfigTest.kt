// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure

import com.openbank.pension.testsupport.ProviderFixtures
import io.smallrye.config.EnvConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Resolves actual service configuration, without process environment or system-property sources. */
class ProviderConfigTest {
    @Test
    fun `development and tests resolve the synthetic provider without environment configuration`() {
        listOf("dev", "test").forEach { profile ->
            assertThat(resolve(profile)).describedAs("provider in %s", profile).isEqualTo(ProviderFixtures.ID)
        }
    }

    @Test
    fun `production without an assigned provider fails configuration resolution`() {
        assertThatThrownBy { resolve("prod") }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessageContaining(PROVIDER_ENV)
    }

    @Test
    fun `explicit environment provider overrides development and production configuration`() {
        val assigned = UUID.fromString("00000000-0000-4000-8000-000000000002")
        listOf("dev", "prod").forEach { profile ->
            assertThat(resolve(profile, mapOf(PROVIDER_ENV to assigned.toString())))
                .describedAs("explicit provider in %s", profile)
                .isEqualTo(assigned)
        }
    }

    @Test
    fun `a malformed configured provider fails instead of using the development fixture`() {
        listOf("dev", "prod").forEach { profile ->
            assertThatThrownBy { resolve(profile, mapOf(PROVIDER_ENV to "not-a-uuid")) }
                .describedAs("invalid explicit provider in %s", profile)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun resolve(profile: String, environment: Map<String, String> = emptyMap()): UUID = SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(
            YamlConfigSource(requireNotNull(javaClass.classLoader.getResource("application.yaml")), YAML_ORDINAL),
            EnvConfigSource(environment, ENV_ORDINAL),
        )
        .withProfile(profile)
        .build()
        .getValue("openbank.pension.provider-entity-id", UUID::class.java)

    private companion object {
        const val PROVIDER_ENV = "OPENBANK_PENSION_PROVIDER_ENTITY_ID"
        const val YAML_ORDINAL = 100
        const val ENV_ORDINAL = 300
    }
}
