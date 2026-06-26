// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.flags

/**
 * Returns the caller-supplied default for every flag, with
 * [EvaluationReason.DEFAULT]. This is the **safe fallback bean**: wire it as the
 * `@Default` `FeatureClient` so a service that has not (yet) deployed a flagd
 * sidecar still runs — every gate simply takes its default branch. Mirrors
 * `AllowAllPolicyDecisionPoint`'s role as the no-sidecar stand-in, but here the
 * neutral outcome is "behave as if the feature were absent", not "allow".
 */
class DefaultsFeatureClient : FeatureClient {
    override fun boolean(flag: String, default: Boolean, ctx: EvalContext) =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)

    override fun string(flag: String, default: String, ctx: EvalContext) =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)

    override fun integer(flag: String, default: Long, ctx: EvalContext) =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)

    override fun double(flag: String, default: Double, ctx: EvalContext) =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
}

/**
 * In-memory [FeatureClient] for unit tests: a method under test that reads a flag
 * can be exercised on both branches without standing up flagd. Unknown flags
 * fall through to the caller default (reason [EvaluationReason.DEFAULT]); known
 * flags resolve with [EvaluationReason.STATIC].
 *
 * ```kotlin
 * val flags = StaticFeatureClient(mapOf("new-router" to true))
 * assertThat(flags.enabled("new-router")).isTrue()
 * assertThat(flags.enabled("absent")).isFalse()
 * ```
 *
 * Values are matched by runtime type; a type mismatch (asking [boolean] for a key
 * whose override is a String) is treated as "not set" and yields the default, so
 * a typo in a test never silently coerces.
 */
class StaticFeatureClient(private val overrides: Map<String, Any?> = emptyMap()) : FeatureClient {
    override fun boolean(flag: String, default: Boolean, ctx: EvalContext) = resolve(flag, default)
    override fun string(flag: String, default: String, ctx: EvalContext) = resolve(flag, default)
    override fun integer(flag: String, default: Long, ctx: EvalContext) = resolve(flag, default)
    override fun double(flag: String, default: Double, ctx: EvalContext) = resolve(flag, default)

    private inline fun <reified T> resolve(flag: String, default: T): FlagEvaluation<T> {
        val raw = overrides[flag]
        return if (raw is T) {
            FlagEvaluation(flag, raw, variant = "static", reason = EvaluationReason.STATIC)
        } else {
            FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
        }
    }
}
