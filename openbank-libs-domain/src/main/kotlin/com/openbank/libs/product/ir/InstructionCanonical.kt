// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.audit.CanonicalJson

/**
 * Canonical JSON of an instruction list: the byte form the determinism gate compares (two runs in
 * one JVM must agree byte for byte) and the golden runner diffs against a scenario's expectation.
 * Amounts are written at [IrLimits.INSTRUCTION_AMOUNT_SCALE]; a `Post` line therefore prints the
 * same bytes whether the arithmetic produced `12.3456` or `12.34560000` — the number is the same.
 */
object InstructionCanonical {

    fun write(instructions: List<Instruction>): String = CanonicalJson.write(instructions.map { tree(it) })

    fun tree(i: Instruction): Map<String, Any?> {
        val head = mapOf("ruleId" to i.ruleId, "revisionHash" to i.revisionHash, "irVersion" to i.irVersion)
        return head + when (i) {
            is Instruction.Post -> mapOf(
                "type" to "POST",
                "narrative" to i.narrative,
                "lines" to i.lines.map {
                    mapOf(
                        "accountingClass" to it.accountingClass,
                        "direction" to it.direction,
                        "amount" to ProductIrCanonical.instructionAmount(it.amount),
                        "currency" to it.currency,
                    )
                },
            )
            is Instruction.Reject -> mapOf("type" to "REJECT", "reasonCode" to i.reasonCode)
            is Instruction.Schedule -> mapOf(
                "type" to "SCHEDULE",
                "hook" to ProductIrParser.HOOK_KEYS.entries.first { it.value == i.hook }.key,
                "tag" to i.tag,
                "cadence" to i.cadence,
                "at" to i.at?.toString(),
            )
            is Instruction.Notify -> mapOf("type" to "NOTIFY", "templateId" to i.templateId, "variables" to i.variables)
            is Instruction.Reprice -> mapOf("type" to "REPRICE", "parameter" to i.parameter, "value" to ProductIrCanonical.rate(i.value))
        }
    }
}
