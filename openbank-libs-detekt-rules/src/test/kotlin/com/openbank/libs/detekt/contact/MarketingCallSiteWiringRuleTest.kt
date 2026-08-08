// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.contact

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Falsified per this repo's own rule that a gate which has only ever passed is unfalsified
 * (`.claude/CLAUDE.md`): [bypassing call site is flagged] and [wired call site with an
 * unmarked helper style survives] are both proven here, not assumed from the rule reading
 * correct.
 */
class MarketingCallSiteWiringRuleTest {

    private val rule = MarketingCallSiteWiringRule()

    @Test
    fun `flags an annotated function whose class injects no ContactPolicyGate`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.libs.contact

            annotation class MarketingCallSite

            class NotificationSender {
                @MarketingCallSite
                suspend fun dispatch(partyId: String) {
                    println("sent to ${'$'}partyId")
                }
            }
            """.trimIndent(),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("has no ContactPolicyGate injected")
    }

    @Test
    fun `flags an annotated function whose class injects the gate but never calls check`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.libs.contact

            annotation class MarketingCallSite
            class ContactPolicyGate

            class NotificationSender(private val gate: ContactPolicyGate) {
                @MarketingCallSite
                suspend fun dispatch(partyId: String) {
                    println("sent to ${'$'}partyId despite ${'$'}gate")
                }
            }
            """.trimIndent(),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("no '<gate>.check(...)' call was found")
    }

    @Test
    fun `passes when the annotated function's class injects the gate and calls check`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.libs.contact

            annotation class MarketingCallSite
            class ContactPolicyGate { suspend fun check(partyId: String): Boolean = true }

            class NotificationSender(private val gate: ContactPolicyGate) {
                @MarketingCallSite
                suspend fun dispatch(partyId: String) {
                    if (gate.check(partyId)) println("sent to ${'$'}partyId")
                }
            }
            """.trimIndent(),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `passes when the gate check happens in a sibling function of the same class`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.libs.contact

            annotation class MarketingCallSite
            class ContactPolicyGate { suspend fun check(partyId: String): Boolean = true }

            class NotificationSender(private val gate: ContactPolicyGate) {
                @MarketingCallSite
                suspend fun dispatch(partyId: String) {
                    if (isAllowed(partyId)) println("sent to ${'$'}partyId")
                }

                private suspend fun isAllowed(partyId: String) = gate.check(partyId)
            }
            """.trimIndent(),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `ignores functions with no MarketingCallSite annotation`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.libs.contact

            class NotificationSender {
                suspend fun dispatch(partyId: String) {
                    println("sent to ${'$'}partyId")
                }
            }
            """.trimIndent(),
        )
        assertThat(findings).isEmpty()
    }
}
