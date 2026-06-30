// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.ml

import com.openbank.fraud.application.port.out.MlModelPort
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H1
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H24
import jakarta.enterprise.context.ApplicationScoped
import kotlin.math.exp

/**
 * Phase-1 throwaway **baseline** model (ADR-0139): a deterministic logistic over the two velocity
 * features. It exists only to exercise the shadow serving path end-to-end with a real, testable
 * score — it is explicitly **not** a trained, card-governed model (ADR-0141 phase 2), and it never
 * affects a verdict (shadow only, ADR-0139 phase 1). The in-process ONNX adapter replaces this class
 * behind [MlModelPort] in phase-1b with zero change to the scoring use case.
 *
 * Pure (no I/O), so it always returns a value; the `null` contract of [MlModelPort] is reserved for
 * the ONNX adapter's inference-unavailable case.
 */
@ApplicationScoped
class BaselineFraudModel : MlModelPort {

    override fun scoreShadow(features: Map<String, Double>): Double? {
        val h1 = features[VELOCITY_TXN_COUNT_H1.name] ?: 0.0
        val h24 = features[VELOCITY_TXN_COUNT_H24.name] ?: 0.0
        val z = INTERCEPT + WEIGHT_H1 * h1 + WEIGHT_H24 * h24
        return 1.0 / (1.0 + exp(-z))
    }

    private companion object {
        // Low base rate; risk rises with short- and longer-window transaction velocity.
        const val INTERCEPT = -4.0
        const val WEIGHT_H1 = 0.30
        const val WEIGHT_H24 = 0.05
    }
}
