// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.authz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `pension_rest_ext_test.rego` proves the customer edge is allowed EXACTLY the pension actions its
 * routes use, over the universe `pension_actions`. That proof is only as good as the universe, so
 * this test derives the universe from the code — every `@Authorize(action = "pension.…")` in
 * src/main — and fails when the rego list and the code disagree in either direction (#12435).
 */
class PensionOpaActionInventoryTest {

    private val moduleDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/kotlin/com/openbank/pension").isDirectory }
    private val regoTest = File(moduleDir, "../openbank-infra/gitops/components/pension/pension_rest_ext_test.rego")

    private fun actionsInCode(): Set<String> = File(moduleDir, "src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { AUTHORIZE.findAll(it.readText()).map { m -> m.groupValues[1] } }
        .toSet()

    private fun regoSet(name: String): Set<String> {
        val text = regoTest.readText()
        val start = text.indexOf("$name := {")
        check(start >= 0) { "$name is not declared in ${regoTest.path}" }
        val body = text.substring(start, text.indexOf("}", start))
        return QUOTED.findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the rego universe is exactly the actions the code authorizes`() {
        val code = actionsInCode()
        // Known-positive: the scan must see actions it cannot miss, or an empty scan would pass.
        assertThat(code).contains("pension.transfer.withdraw", "pension.contract.inspect", "pension.funding.operate")
        assertThat(regoSet("pension_actions")).isEqualTo(code)
    }

    @Test
    fun `every edge route action is an action the code authorizes`() {
        assertThat(actionsInCode()).containsAll(regoSet("edge_route_actions"))
    }

    private companion object {
        val AUTHORIZE = Regex("""@Authorize\(\s*action\s*=\s*"(pension\.[a-z.-]+)"""")
        val QUOTED = Regex(""""(pension\.[a-z.-]+)"""")
    }
}
