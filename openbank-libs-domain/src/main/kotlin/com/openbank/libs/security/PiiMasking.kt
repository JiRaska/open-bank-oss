// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

/**
 * Strategies for masking personally identifiable information in API responses and logs.
 *
 * All strategies preserve enough characters for support agents to verify identity over the
 * phone ("the email ending in -ka@gmail.com, IBAN ending 1234") without leaking the full
 * value. None of the strategies are reversible.
 *
 * Use [PiiMask] to apply them — explicitly, at the point the value is rendered or logged.
 *
 * There is deliberately **no `@MaskSensitive` annotation** (#4011). One existed and its KDoc
 * described the contract it did not have: it claimed to "flag DTO fields for downstream
 * serialization filters (admin-ui proxy, audit-event sanitizer)", and neither filter was ever
 * written. A field marked with it serialised in full while the source read as protected —
 * silent disclosure on a GDPR-facing path, which is worse than no marker at all. Any
 * declarative masking must land together with the serializer that honours it.
 *
 * GDPR Art. 32 (security of processing) + Art. 25 (data protection by design).
 */
enum class MaskStrategy { EMAIL, IBAN, PAN, PHONE, NAME, NATIONAL_ID, FULL, NONE }

object PiiMask {

    /** `john.doe@example.com` → `j***e@example.com`. Empty/short inputs become `***`. */
    fun email(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val at = input.indexOf('@')
        if (at <= 0) return "***"
        val local = input.substring(0, at)
        val domain = input.substring(at)
        val masked = when {
            local.length <= 2 -> "*".repeat(local.length)
            else -> "${local.first()}${"*".repeat(local.length - 2)}${local.last()}"
        }
        return "$masked$domain"
    }

    /** `CZ6508000000192000145399` → `CZ65********5399`. Spaces stripped. */
    fun iban(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val compact = input.replace(" ", "")
        if (compact.length < 8) return "*".repeat(compact.length)
        return compact.substring(0, 4) + "*".repeat(compact.length - 8) + compact.substring(compact.length - 4)
    }

    /** `4532015112830366` → `4532********0366`. PCI-DSS compliant (first 6 + last 4 are permitted; we show first 4 only). */
    fun pan(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val compact = input.replace(Regex("[\\s-]"), "")
        if (compact.length < 8) return "*".repeat(compact.length)
        return compact.substring(0, 4) + "*".repeat(compact.length - 8) + compact.substring(compact.length - 4)
    }

    /** `+420123456789` → `+420*****6789`. Keeps country code (first 4 chars incl. `+`) and last 4 digits. */
    fun phone(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val compact = input.replace(Regex("[\\s-]"), "")
        if (compact.length < 8) return "*".repeat(compact.length)
        return compact.substring(0, 4) + "*".repeat(compact.length - 8) + compact.substring(compact.length - 4)
    }

    /** `Jiří Raška` → `J. R.`. Multi-word names collapse to initials. */
    fun name(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "${it.first()}." }
    }

    /** `8501010987` (CZ rodné číslo) → `850101****`. Keeps date portion, masks suffix. */
    fun nationalId(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val compact = input.replace(Regex("[\\s/-]"), "")
        if (compact.length <= 6) return "*".repeat(compact.length)
        return compact.substring(0, 6) + "*".repeat(compact.length - 6)
    }

    /** Replaces every character with `*`. */
    fun full(input: String?): String = "*".repeat(input?.length ?: 0)

    fun apply(strategy: MaskStrategy, input: String?): String = when (strategy) {
        MaskStrategy.EMAIL -> email(input)
        MaskStrategy.IBAN -> iban(input)
        MaskStrategy.PAN -> pan(input)
        MaskStrategy.PHONE -> phone(input)
        MaskStrategy.NAME -> name(input)
        MaskStrategy.NATIONAL_ID -> nationalId(input)
        MaskStrategy.FULL -> full(input)
        MaskStrategy.NONE -> input.orEmpty()
    }
}
