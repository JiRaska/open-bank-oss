// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

/**
 * Single port every feature-flag evaluation goes through — the hexagonal
 * counterpart to the flagd sidecar deployed per service (ADR-0067), exactly as
 * [com.openbank.libs.authz.PolicyDecisionPoint] is to the OPA sidecar.
 *
 * The shape (typed accessors + [EvalContext] + [EvaluationReason]) is
 * deliberately **OpenFeature-aligned** so that a Phase-2 delegation to the
 * upstream `dev.openfeature:sdk` is a drop-in and so that the FE (admin-ui via
 * BFF) and KMP customer app speak the same contract. Implementations:
 *
 *   - `FlagdProvider`          (prod)  → OFREP over `localhost:8016` (openbank-libs-runtime)
 *   - [DefaultsFeatureClient]  (fallback / advisory) → always returns the default
 *   - [StaticFeatureClient]    (tests) → in-memory map of overrides
 *
 * ### Fail-static contract
 * Evaluation is on the request hot path and **must never throw**: a provider
 * outage, a parse error, or a missing flag resolves to the caller-supplied
 * `default` with [EvaluationReason.ERROR] (provider failure) or
 * [EvaluationReason.DEFAULT] (flag absent). Callers therefore never need a
 * try/catch around an eval — the default is the safe behaviour by construction.
 *
 * Evaluation is synchronous: flagd evaluates locally (sub-ms), so a `suspend`
 * signature would only add ceremony. Outbound exposure emission (A/B) is
 * fire-and-forget through [com.openbank.libs.flags.ExposurePublisher].
 */
interface FeatureClient {
    fun boolean(flag: String, default: Boolean, ctx: EvalContext = EvalContext.EMPTY): FlagEvaluation<Boolean>
    fun string(flag: String, default: String, ctx: EvalContext = EvalContext.EMPTY): FlagEvaluation<String>
    fun integer(flag: String, default: Long, ctx: EvalContext = EvalContext.EMPTY): FlagEvaluation<Long>
    fun double(flag: String, default: Double, ctx: EvalContext = EvalContext.EMPTY): FlagEvaluation<Double>

    /** Convenience for the common boolean gate — true only when the flag resolves on. */
    fun enabled(flag: String, ctx: EvalContext = EvalContext.EMPTY): Boolean = boolean(flag, default = false, ctx).value
}

/**
 * Evaluation context forwarded to the provider's targeting rules — the
 * OpenFeature `EvaluationContext`.
 *
 * [targetingKey] is the stable bucketing key for percentage rollout and A/B
 * splits; the provider hashes it so the same subject always lands in the same
 * cohort. **It must be pseudonymous** — a party id or session id, never raw PII
 * (GDPR Art. 30, ADR-0067 compliance note). [attributes] carry any extra
 * dimensions targeting may rely on (tenant, channel, app version, country).
 */
data class EvalContext(val targetingKey: String? = null, val attributes: Map<String, Any?> = emptyMap()) {
    companion object {
        val EMPTY: EvalContext = EvalContext()
    }
}

/**
 * The outcome of one evaluation. Mirrors the OpenFeature evaluation details so
 * the audit trail and the admin-ui can answer "why did this subject get this
 * value": which [variant] matched, the [reason] code, and — for diagnostics —
 * the [errorCode] when the provider failed (value is still the safe default).
 */
data class FlagEvaluation<T>(
    val flagKey: String,
    val value: T,
    val variant: String? = null,
    val reason: EvaluationReason = EvaluationReason.UNKNOWN,
    val errorCode: String? = null,
) {
    /** True when the provider actually decided this value (not a fallback default/error). */
    val resolved: Boolean
        get() = reason == EvaluationReason.STATIC ||
            reason == EvaluationReason.TARGETING_MATCH ||
            reason == EvaluationReason.SPLIT
}

/**
 * OpenFeature reason codes (subset). Kept identical to the spec so a Phase-2
 * swap to the upstream SDK does not change downstream audit/analytics meaning.
 *
 *   - [STATIC]           — flag has a single, non-targeted value.
 *   - [TARGETING_MATCH]  — a targeting rule matched this context.
 *   - [SPLIT]            — value chosen by a percentage/fractional (A/B) split.
 *   - [DISABLED]         — flag exists but is turned off (kill-switch / state off).
 *   - [DEFAULT]          — flag not found; caller default returned.
 *   - [ERROR]            — provider failed; caller default returned ([errorCode] set).
 *   - [UNKNOWN]          — not yet evaluated (initial value).
 */
enum class EvaluationReason { STATIC, TARGETING_MATCH, SPLIT, DISABLED, DEFAULT, ERROR, UNKNOWN }

/**
 * Governance classification of a flag (ADR-0067 §5). Drives *how* a flip is
 * allowed to happen, not how the flag evaluates:
 *
 *   - [COSMETIC]    — UI-only / no behavioural risk. Flip freely via git.
 *   - [FEATURE]     — backend behaviour change, non-money-path. Git review.
 *   - [MONEY_PATH]  — touches a money-path service (`rules.yaml: money_path_services`).
 *                     A flip is four-eyes-gated (`libs/foureyes`) and emits an
 *                     `AuditEvent` (`operation = "featureflag.flip"`).
 */
enum class FlagClassification { COSMETIC, FEATURE, MONEY_PATH }
