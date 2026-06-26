// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.infrastructure.flags

import com.openbank.libs.flags.EvalContext
import com.openbank.libs.flags.EvaluationReason
import com.openbank.libs.flags.FeatureClient
import com.openbank.libs.flags.FlagEvaluation
import io.quarkus.test.Mock

/**
 * Test-scope [FeatureClient] that enables all boolean flags — prevents
 * [@FeatureFlag][com.openbank.libs.flags.FeatureFlag]-gated endpoints from
 * returning 503 in [@QuarkusTest][io.quarkus.test.junit.QuarkusTest] scenarios
 * where flagd is not running.
 *
 * Declared with [@Mock][io.quarkus.test.Mock] so Quarkus registers it as
 * `@Alternative @Priority(1)` and it overrides the production
 * [FlagdProducer]-provided bean automatically in every `@QuarkusTest`.
 */
@Mock
class TestFeatureClient : FeatureClient {

    /** All boolean flags → true in tests (feature enabled). */
    override fun boolean(flag: String, default: Boolean, ctx: EvalContext): FlagEvaluation<Boolean> =
        FlagEvaluation(flag, true, variant = "test-on", reason = EvaluationReason.STATIC)

    override fun string(flag: String, default: String, ctx: EvalContext): FlagEvaluation<String> =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)

    override fun integer(flag: String, default: Long, ctx: EvalContext): FlagEvaluation<Long> =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)

    override fun double(flag: String, default: Double, ctx: EvalContext): FlagEvaluation<Double> =
        FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
}
