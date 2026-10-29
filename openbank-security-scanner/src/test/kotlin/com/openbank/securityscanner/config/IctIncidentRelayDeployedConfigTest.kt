// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #4942: `openbank.security.ict.incident` sat at end offset 0 because the deployed
 * manifest overrode `OPENBANK_OUTBOX_DISPATCH_ENABLED` to "false", so incident events never left
 * the outbox. `IctIncidentDurabilityIT` proves the relay works when enabled; it cannot see the
 * DEPLOYED value. This pins both the in-repo default and the gitops override.
 */
class IctIncidentRelayDeployedConfigTest {
    private val manifest: Path =
        Path.of("..", "openbank-infra", "gitops", "components", "security-scanner", "security-scanner-service.yaml")
    private val appYaml: Path = Path.of("src", "main", "resources", "application.yaml")

    @Test
    fun `gitops manifest does not disable the outbox dispatcher`() {
        val lines = Files.readAllLines(manifest)
        val idx = lines.indexOfFirst { it.trim() == "- name: OPENBANK_OUTBOX_DISPATCH_ENABLED" }
        assertThat(idx).describedAs("manifest must declare the switch explicitly").isGreaterThanOrEqualTo(0)
        assertThat(lines[idx + 1].trim()).isEqualTo("value: \"true\"")
    }

    @Test
    fun `application yaml enables the outbox dispatcher`() {
        val text = Files.readAllLines(appYaml)
        assertThat(text.map { it.trim() }).contains("dispatch-enabled: true")
    }
}
