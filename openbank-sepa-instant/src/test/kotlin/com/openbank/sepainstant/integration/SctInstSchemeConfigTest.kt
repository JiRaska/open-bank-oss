// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** Prevent a GitOps/app env-name mismatch from silently selecting the false default again. */
class SctInstSchemeConfigTest {
    @Test
    fun `scheme flag has one explicit inactive GitOps value and matching application placeholder`() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "openbank-infra/gitops/components/payments/payments-services.yaml").isFile }
        val gitops = File(root, "openbank-infra/gitops/components/payments/payments-services.yaml").readText()
        val application = File(root, "openbank-sepa-instant/src/main/resources/application.yaml").readText()

        assertThat(application).contains("enabled: \${SCT_INST_SCHEME_SUBMISSION_ENABLED:false}")
        assertThat(gitops).contains("- name: SCT_INST_SCHEME_SUBMISSION_ENABLED\n              value: \"false\"")
        assertThat(gitops).doesNotContain("SEPA_INSTANT_SCHEME_SUBMISSION_ENABLED")
    }
}
