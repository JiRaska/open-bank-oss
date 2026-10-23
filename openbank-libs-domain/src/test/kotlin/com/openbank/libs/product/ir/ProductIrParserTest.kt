// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductIrParserTest {

    private fun problemsOf(json: String): List<String> {
        var caught: ProductIrValidationException? = null
        try {
            ProductIrParser.parse(json)
        } catch (e: ProductIrValidationException) {
            caught = e
        }
        assertThat(caught).withFailMessage("expected the document to be rejected").isNotNull
        return caught!!.problems.map { "${it.path}: ${it.message}" }
    }

    /** Edits the savings fixture at the JSON-text level: the fixture is the positive control. */
    private fun savingsWith(find: String, replace: String): String {
        val json = IrFixtures.savingsJson
        check(json.contains(find)) { "fixture does not contain '$find'" }
        return json.replace(find, replace)
    }

    @Test
    fun `parses the savings fixture into a typed revision with a 64-hex hash`() {
        val rev = IrFixtures.savings()
        assertThat(rev.irVersion).isEqualTo(1)
        assertThat(rev.productCode).isEqualTo("SAV-FIXED")
        assertThat(rev.interest!!.rate).isEqualByComparingTo(BigDecimal("0.025"))
        assertThat(rev.interest!!.bonus!!.predicate).isInstanceOf(Predicate.And::class.java)
        assertThat(rev.fees).hasSize(1)
        assertThat(rev.hooks[Hook.SCHEDULED]).hasSize(3)
        assertThat(rev.hooks[Hook.PRE_POSTING]!!.single().action).isEqualTo(Action.Reject("INSUFFICIENT_FUNDS"))
        assertThat(rev.revisionHash).matches("[0-9a-f]{64}")
        assertThat(
            rev.allRuleIds,
        ).doesNotHaveDuplicates().contains("interest.base", "interest.bonus", "fee.maintenance")
    }

    @Test
    fun `parses the term-deposit fixture with contiguous tiers and a penalty`() {
        val rev = IrFixtures.termDeposit()
        assertThat(rev.interest!!.rateType).isEqualTo(RateType.TIERED)
        assertThat(rev.interest!!.tiers.map { it.to }).containsExactly(BigDecimal("10000.00"), null)
        assertThat(rev.termDeposit!!.earlyWithdrawal!!.kind).isEqualTo(PenaltyKind.RATE)
    }

    @Test
    fun `rejects an unknown key anywhere`() {
        assertThat(
            problemsOf(
                savingsWith("\"productCode\": \"SAV-FIXED\",", "\"productCode\": \"SAV-FIXED\", \"script\": \"x\","),
            ),
        ).anyMatch {
            it ==
                "/script: unknown key"
        }
        assertThat(
            problemsOf(savingsWith("\"feeCode\": \"MAINT\",", "\"feeCode\": \"MAINT\", \"extra\": 1,")),
        ).anyMatch {
            it ==
                "/fees/0/extra: unknown key"
        }
    }

    @Test
    fun `rejects a missing ruleId`() {
        assertThat(problemsOf(savingsWith("\"ruleId\": \"fee.maintenance\",", ""))).anyMatch {
            it ==
                "/fees/0/ruleId: required"
        }
    }

    @Test
    fun `rejects a duplicate ruleId across sections`() {
        assertThat(
            problemsOf(savingsWith("\"ruleId\": \"fee.maintenance\"", "\"ruleId\": \"interest.base\"")),
        ).anyMatch {
            it.contains("duplicate ruleId 'interest.base'")
        }
    }

    @Test
    fun `rejects a ruleId that looks like an index`() {
        assertThat(problemsOf(savingsWith("\"ruleId\": \"open.welcome\"", "\"ruleId\": \"3\""))).anyMatch {
            it.startsWith("/hooks/onOpen/3/ruleId: must match")
        }
    }

    @Test
    fun `rejects a tier list over the limit and a non-contiguous tier list`() {
        val tooMany = (0..IrLimits.MAX_TIERS).joinToString(",") { i ->
            val to = if (i == IrLimits.MAX_TIERS) "" else ", \"to\": \"${(i + 1) * 1000}\""
            "{ \"from\": \"${i * 1000}\"$to, \"rate\": \"0.01\" }"
        }
        val json = IrFixtures.termDepositJson.replace(Regex("\"tiers\": \\[[^\\]]*\\]"), "\"tiers\": [$tooMany]")
        assertThat(problemsOf(json)).anyMatch { it == "/interest/tiers: more than ${IrLimits.MAX_TIERS} items (limit)" }

        val gap = IrFixtures.termDepositJson.replace(
            "{ \"from\": \"10000.00\", \"rate\": \"0.0300\" }",
            "{ \"from\": \"20000.00\", \"rate\": \"0.0300\" }",
        )
        assertThat(problemsOf(gap)).anyMatch { it.startsWith("/interest/tiers/1/from: tiers must be contiguous") }
    }

    @Test
    fun `rejects a predicate deeper than the limit`() {
        var inner = "{ \"type\": \"noEvents\", \"kind\": \"WITHDRAWAL\", \"window\": { \"days\": 1 } }"
        repeat(IrLimits.MAX_PREDICATE_DEPTH) { inner = "{ \"type\": \"not\", \"operand\": $inner }" }
        val json = savingsWith(
            "\"waiver\": { \"type\": \"comparison\", \"attribute\": \"BALANCE\", \"operator\": \"GTE\", \"thres" +
                "hold\": \"50000.00\", \"currency\": \"EUR\" }",
            "\"waiver\": $inner",
        )
        assertThat(problemsOf(json)).anyMatch {
            it.endsWith("predicate deeper than ${IrLimits.MAX_PREDICATE_DEPTH} (limit)")
        }
    }

    @Test
    fun `rejects precision beyond the canonical scale`() {
        assertThat(problemsOf(savingsWith("\"rate\": \"0.0250\"", "\"rate\": \"0.02500000001\""))).anyMatch {
            it.contains("more than ${IrLimits.RATE_SCALE} decimal places")
        }
        assertThat(problemsOf(savingsWith("\"amount\": \"5.00\"", "\"amount\": 5.00001"))).anyMatch {
            it.contains("more than ${IrLimits.AMOUNT_SCALE} decimal places")
        }
    }

    @Test
    fun `rejects a Reject outside prePosting and an unknown action`() {
        val json = savingsWith(
            "\"action\": { \"type\": \"NOTIFY\", \"templateId\": \"savings-welcome\", \"variables\": { \"produc" +
                "tCode\": \"SAV-FIXED\" } }",
            "\"action\": { \"type\": \"REJECT\", \"reasonCode\": \"NOPE\" }",
        )
        assertThat(problemsOf(json)).anyMatch {
            it ==
                "/hooks/onOpen/3/action/type: REJECT is allowed on prePosting only"
        }
        assertThat(problemsOf(savingsWith("\"type\": \"ACCRUE_INTEREST\"", "\"type\": \"RUN_SCRIPT\""))).anyMatch {
            it.contains("unknown action type 'RUN_SCRIPT'")
        }
    }

    @Test
    fun `rejects a trigger nothing schedules and a schedule nothing consumes`() {
        assertThat(problemsOf(savingsWith("\"trigger\": \"ACCRUAL\"", "\"trigger\": \"GHOST\"")))
            .anyMatch { it.contains("trigger 'GHOST' is produced by no Schedule action") }
            .anyMatch { it.contains("Schedule tag 'ACCRUAL' is consumed by no scheduled rule") }
    }

    @Test
    fun `rejects a same-cycle schedule that targets its own hook`() {
        val json = savingsWith(
            "{ \"ruleId\": \"scheduled.fee\", \"trigger\": \"FEE_MAINT\", \"action\": { \"type\": \"CHARGE_FEE" +
                "\", \"feeRuleId\": \"fee.maintenance\" } }",
            "{ \"ruleId\": \"scheduled.fee\", \"trigger\": \"FEE_MAINT\", \"action\": { \"type\": \"CHARGE_FEE" +
                "\", \"feeRuleId\": \"fee.maintenance\" } }," +
                "{ \"ruleId\": \"scheduled.loop\", \"trigger\": \"LOOP\", \"action\": { \"type\": \"SCHEDULE\"," +
                " \"hook\": \"scheduled\", \"tag\": \"LOOP\", \"cadence\": \"ON_EVENT\" } }",
        )
        assertThat(problemsOf(json))
            .anyMatch { it.contains("a same-cycle Schedule may not target the hook that emits it") }
            .anyMatch { it.contains("form a cycle") }
    }

    @Test
    fun `a same-cycle schedule across hooks is allowed when acyclic`() {
        val json = savingsWith(
            "\"onParameterChange\": [",
            "\"postPosting\": [ { \"ruleId\": \"post.sweep\", \"action\": { \"type\": \"SCHEDULE\", \"hook\": " +
                "\"scheduled\", \"tag\": \"SWEEP\", \"cadence\": \"ON_EVENT\" } } ],\n" +
                "\"onParameterChange\": [",
        ).replace(
            "{ \"ruleId\": \"scheduled.fee\", \"trigger\": \"FEE_MAINT\", \"action\": { \"type\": \"CHARGE_FEE" +
                "\", \"feeRuleId\": \"fee.maintenance\" } }",
            "{ \"ruleId\": \"scheduled.fee\", \"trigger\": \"FEE_MAINT\", \"action\": { \"type\": \"CHARGE_FEE" +
                "\", \"feeRuleId\": \"fee.maintenance\" } }," +
                "{ \"ruleId\": \"scheduled.sweep\", \"trigger\": \"SWEEP\", \"action\": { \"type\": \"NOTIFY\"," +
                " \"templateId\": \"swept\", \"variables\": {} } }",
        )
        assertThat(ProductIrParser.parse(json).hooks[Hook.POST_POSTING]).hasSize(1)
    }

    @Test
    fun `rejects an unsupported irVersion, a bad currency, a missing section and a bad fee reference`() {
        assertThat(problemsOf(savingsWith("\"irVersion\": 1", "\"irVersion\": 2"))).anyMatch {
            it.startsWith("/irVersion: unsupported irVersion 2")
        }
        assertThat(problemsOf(savingsWith("\"currency\": \"EUR\",", "\"currency\": \"EUX\","))).anyMatch {
            it ==
                "/currency: not an ISO 4217 code"
        }
        assertThat(
            problemsOf(savingsWith("\"feeRuleId\": \"fee.maintenance\"", "\"feeRuleId\": \"fee.ghost\"")),
        ).anyMatch {
            it.contains("no fee with ruleId 'fee.ghost'")
        }
        val noInterest = IrFixtures.savingsJson.replace(
            Regex("\"interest\": \\{.*?\\n  \\},\\n", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        assertThat(problemsOf(noInterest)).anyMatch { it == "/hooks/scheduled/0/action: needs an interest section" }
    }

    @Test
    fun `rejects FIXED with tiers, TIERED with rate, and penalty kind mismatches`() {
        assertThat(problemsOf(savingsWith("\"rate\": \"0.0250\",", "\"rate\": \"0.0250\", \"tiers\": [],")))
            .anyMatch { it == "/interest/tiers: not allowed for rateType FIXED" }
        assertThat(
            problemsOf(
                IrFixtures.termDepositJson.replace(
                    "\"rateType\": \"TIERED\",",
                    "\"rateType\": \"TIERED\", \"rate\": \"0.01\",",
                ),
            ),
        )
            .anyMatch { it == "/interest/rate: not allowed for rateType TIERED" }
        assertThat(
            problemsOf(
                IrFixtures.termDepositJson.replace(
                    "\"kind\": \"RATE\", \"rate\": \"0.0100\"",
                    "\"kind\": \"RATE\", \"rate\": \"0.0100\", \"fraction\": \"0.5\"",
                ),
            ),
        )
            .anyMatch { it == "/termDeposit/earlyWithdrawal/fraction: not allowed for kind RATE" }
        assertThat(
            problemsOf(
                IrFixtures.termDepositJson.replace(
                    "\"kind\": \"RATE\", \"rate\": \"0.0100\"",
                    "\"kind\": \"FORFEITED_INTEREST_FRACTION\", \"fraction\": \"1.5\"",
                ),
            ),
        )
            .anyMatch { it == "/termDeposit/earlyWithdrawal/fraction: must be within [0, 1]" }
    }

    @Test
    fun `rejects wrong value types with a path`() {
        assertThat(problemsOf(savingsWith("\"irVersion\": 1", "\"irVersion\": \"1\""))).anyMatch {
            it ==
                "/irVersion: expected an integer"
        }
        assertThat(problemsOf(savingsWith("\"fees\": [", "\"fees\": 3, \"termDeposit\": ["))).anyMatch {
            it ==
                "/fees: expected an array"
        }
        assertThat(problemsOf(savingsWith("\"days\": 30", "\"days\": 0"))).anyMatch {
            it.contains("/window/days: must be between 1 and ${IrLimits.MAX_WINDOW_DAYS}")
        }
        assertThat(
            problemsOf(
                savingsWith(
                    "\"operator\": \"GTE\", \"threshold\": \"50000.00\"",
                    "\"operator\": \"LIKE\", \"threshold\": \"50000.00\"",
                ),
            ),
        ).anyMatch {
            it.contains("/fees/0/waiver/operator: must be one of")
        }
        assertThat(
            problemsOf(
                savingsWith(
                    "\"attribute\": \"BALANCE\", \"operator\": \"GTE\", \"threshold\": \"50000.00\", \"currency" +
                        "\": \"EUR\"",
                    "\"attribute\": \"SEGMENT\", \"operator\": \"GT\", \"textValue\": \"VIP\"",
                ),
            ),
        ).anyMatch {
            it.contains("a text attribute allows EQ or NEQ only")
        }
    }

    @Test
    fun `the top level must be an object`() {
        assertThat(problemsOf("[]")).anyMatch { it == "/: expected an object" }
    }
}
