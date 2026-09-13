// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * Model-based content safety in front of (and behind) every reasoning loop — the second half of the
 * ADR-0031 `guardrails: [llama-guard, prompt-injection-filter]` declaration.
 *
 * The first half is deterministic and already live: copilot-service's `PromptInjectionGuard` matches
 * a high-precision regex set. This port is the complementary classifier: a small safety model
 * (Llama Guard, served through the ADR-0174 LiteLLM gateway) that judges free text the pattern set
 * cannot — self-harm, weapons, fraud coaching, PII exfiltration phrased in ways no regex enumerates.
 * Neither replaces the other: the regex is cheap, offline and cannot be talked out of a match; the
 * classifier generalises but costs a network round trip and can be unavailable.
 *
 * ## Why [Decision.UNAVAILABLE] is its own value and not `SAFE`
 *
 * A guardrail that cannot reach its model has learned nothing about the text. Folding that into
 * `SAFE` is the exact shape that shipped a notification channel reporting every push as delivered
 * while APNs credentials were absent: a disabled/degraded path returning the success value, with no
 * signal anywhere disagreeing. The caller must decide what an unavailable verdict means for ITS
 * risk — the money-path answer (fail closed) and the help-desk answer (proceed, audited) are
 * different, and only the caller knows which it is. [SafetyVerdict.isBlocking] takes that choice as
 * an explicit argument rather than defaulting it.
 *
 * Pure domain: no framework imports, no HTTP. The runtime adapter
 * (`openbank-libs-runtime`: `LlamaGuardContentSafetyAdapter`) owns the transport.
 */
interface ContentSafetyPort {

    /**
     * Classify one piece of text in the role it plays in the conversation.
     *
     * Implementations must never throw: a guardrail that crashes the request it was protecting is
     * worse than one that reports [Decision.UNAVAILABLE] and lets the caller apply its policy.
     */
    suspend fun classify(role: SafetyRole, text: String): SafetyVerdict

    /** Which side of the conversation the text came from — Llama Guard classifies these differently. */
    enum class SafetyRole {
        /** Untrusted input from a human or an upstream system. */
        USER,

        /** What the model is about to say back. Catches an unsafe completion the input did not predict. */
        ASSISTANT,
    }

    enum class Decision {
        SAFE,
        UNSAFE,

        /**
         * No classification was obtained — model unreachable, unconfigured, timed out, or an
         * unparseable answer. NOT a synonym for safe; see the class KDoc.
         */
        UNAVAILABLE,
    }

    /**
     * @param categories the model's own hazard codes (e.g. `S2`, `S9`) when it returned any.
     *   Empty for [Decision.SAFE] and usually empty for [Decision.UNAVAILABLE].
     * @param model the model id as sent upstream, so an audit row and a Prometheus series can name
     *   what actually judged the text rather than "the guardrail".
     * @param reason short, closed-vocabulary detail for [Decision.UNAVAILABLE]
     *   (`not_configured`, `transport`, `unparseable`) — a Prometheus label, so never free text.
     */
    data class SafetyVerdict(
        val decision: Decision,
        val categories: List<String> = emptyList(),
        val model: String = "",
        val reason: String = "",
    ) {
        /**
         * Whether the caller must stop.
         *
         * @param failClosed what an [Decision.UNAVAILABLE] verdict means here. `true` on money-path
         *   and any state-changing surface; `false` where a help answer degrading to "no classifier
         *   ran" is preferable to refusing to talk. There is deliberately no default.
         */
        fun isBlocking(failClosed: Boolean): Boolean = when (decision) {
            Decision.UNSAFE -> true
            Decision.UNAVAILABLE -> failClosed
            Decision.SAFE -> false
        }
    }

    companion object {
        const val REASON_NOT_CONFIGURED = "not_configured"
        const val REASON_TRANSPORT = "transport"
        const val REASON_UNPARSEABLE = "unparseable"

        /**
         * Classifies nothing and says so. The default for any caller not yet wired — it reports
         * [Decision.UNAVAILABLE], never [Decision.SAFE], so an unwired guardrail is visible in the
         * metric instead of looking like a clean bill of health.
         */
        val DISABLED: ContentSafetyPort = object : ContentSafetyPort {
            override suspend fun classify(role: SafetyRole, text: String): SafetyVerdict =
                SafetyVerdict(Decision.UNAVAILABLE, reason = REASON_NOT_CONFIGURED)
        }
    }
}
