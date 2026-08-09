// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure

import io.quarkus.runtime.StartupEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The committed defaults must name the estate region, and the estate region must be bootable (#3962).
 *
 * `eu-north-1` is established by **ADR-0175 §Decision 1** ("The estate is `eu-north-1` (Stockholm)").
 * §Decision 2 of the same ADR explicitly does *not* adopt the `eu-central-1` production pin that
 * `openbank-infra/aws/README.md` infers from a condition ADR-0027 does not contain — so the ADR, not
 * the README, is the source asserted here.
 *
 * Before the fix all four assertions below were red: the defaults were `eu-central-1` with an
 * allow-list of `eu-central-1,eu-west-1`, so the *only* configuration that booted was the one that
 * misstated the region, and configuring the true region aborted startup (`enforce` defaults to true).
 *
 * Values are asserted literally on purpose. A `isNotNull()`/"is some region" assertion passes against
 * exactly the wrong value this test exists to reject.
 */
class DataResidencyDefaultsTest {

    private companion object {
        /** ADR-0175 §Decision 1. Not from a README — see the class KDoc. */
        const val ESTATE_REGION = "eu-north-1"
        val APPLICATION_YAML = File("src/main/resources/application.yaml")
    }

    /** Extracts `key: ${ENV_VAR:default}` from application.yaml, returning the committed default. */
    private fun committedDefault(envVar: String): String {
        assertThat(APPLICATION_YAML).exists()
        val pattern = Regex("""\$\{$envVar:([^}]*)}""")
        val match = pattern.find(APPLICATION_YAML.readText())
        assertThat(match).describedAs("no default for %s in application.yaml", envVar).isNotNull
        return match!!.groupValues[1]
    }

    private fun annotationDefault(field: String): String = DataResidencyValidator::class.java
        .getDeclaredField(field)
        .getAnnotation(ConfigProperty::class.java)
        .defaultValue

    @Test
    fun `the committed residency defaults name the estate region`() {
        assertThat(committedDefault("ANALYTICS_RESIDENCY_REGION")).isEqualTo(ESTATE_REGION)
        assertThat(annotationDefault("region")).isEqualTo(ESTATE_REGION)
    }

    @Test
    fun `the estate region is on the committed allow-list`() {
        val yamlAllowed = committedDefault("ANALYTICS_RESIDENCY_ALLOWED").split(",").map { it.trim() }
        assertThat(yamlAllowed).contains(ESTATE_REGION)
        val annotationAllowed = annotationDefault("allowed").split(",").map { it.trim() }
        assertThat(annotationAllowed).contains(ESTATE_REGION)
    }

    @Test
    fun `the committed defaults boot with enforcement on`() {
        val validator = DataResidencyValidator().apply {
            region = committedDefault("ANALYTICS_RESIDENCY_REGION")
            allowed = committedDefault("ANALYTICS_RESIDENCY_ALLOWED")
            enforce = true
        }
        assertThatCode { validator.onStart(StartupEvent()) }.doesNotThrowAnyException()
    }

    /** Falsification control: the guard must still abort on a region genuinely outside the estate. */
    @Test
    fun `a region outside the allow-list still aborts boot`() {
        val validator = DataResidencyValidator().apply {
            region = "us-east-1"
            allowed = committedDefault("ANALYTICS_RESIDENCY_ALLOWED")
            enforce = true
        }
        assertThatThrownBy { validator.onStart(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("data residency violation")
    }

    @Test
    fun `the committed WORM S3 defaults name the estate region`() {
        assertThat(committedDefault("ANALYTICS_WORM_S3_REGION")).isEqualTo(ESTATE_REGION)
        assertThat(committedDefault("ANALYTICS_WORM_S3_ENDPOINT"))
            .isEqualTo("https://s3.$ESTATE_REGION.amazonaws.com")
    }
}
