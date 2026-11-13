// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.monitoring

import com.openbank.libs.fincrime.AmlCaseSeverity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MonitoringPackLoaderTest {

    @Test
    fun `the shipped synthetic pack compiles against its vocabularies, features and counter scheme`() {
        val pack = MonitoringPackFixture.load()
        assertThat(pack.identity.id).endsWith(".synthetic")
        assertThat(pack.identity.contentHash).matches("[0-9a-f]{64}")
        assertThat(pack.rules.map { it.id }).containsExactlyInAnyOrder(
            "syn-structuring-czk-out",
            "syn-structuring-czk-in",
            "syn-structuring-eur-out",
        )
        assertThat(pack.features.keys).hasSize(3)
        assertThat(pack.severityOf("syn-structuring-eur-out")).isEqualTo(AmlCaseSeverity.HIGH)
        assertThat(pack.longestWindowSeconds).isEqualTo(86_400L)
    }

    @Test
    fun `a feature whose window was edited no longer matches the hash the pack pins`() {
        val edited = MonitoringPackFixture.file(
            "monitoring-count-features.synthetic.json",
        ).replaceFirst("86400", "3600")
        assertThatThrownBy { MonitoringPackFixture.load(features = edited) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("another revision or content")
    }

    @Test
    fun `a pack that pins an undefined feature, an unknown code or an unknown band does not load`() {
        val features = MonitoringPackFixture.file("monitoring-count-features.synthetic.json")
        val pack = MonitoringPackFixture.file("monitoring-pack.synthetic.json")
        assertThatThrownBy {
            MonitoringPackFixture.load(features = features.replace("syn_eur_out_below_mark_w24h", "renamed_feature"))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("undefined feature")
        assertThatThrownBy { MonitoringPackFixture.load(pack = pack.replace("\"HIGH\"", "\"SEVERE\"")) }
            .describedAs(
                "a severity code the vocabulary does not hold",
            ).isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            MonitoringPackFixture.load(pack = pack.replace("SYN_REPEATED_BELOW_MARK", "UNLISTED_REASON"))
        }
            .describedAs(
                "a reason code the vocabulary does not hold",
            ).isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonitoringPackFixture.load(scheme = MonitoringPackFixture.scheme.copy(revision = 2)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("another counter scheme")
        assertThatThrownBy {
            MonitoringPackFixture.load(scheme = MonitoringPackFixture.scheme.copy(bands = emptyList()))
        }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("band the scheme does not define")
        assertThatThrownBy { MonitoringPackFixture.load(features = features.replace("\"purpose\"", "\"porpoise\"")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
