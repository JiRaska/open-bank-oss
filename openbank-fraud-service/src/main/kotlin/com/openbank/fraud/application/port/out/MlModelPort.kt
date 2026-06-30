// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

/**
 * Outbound port for the ML model serving plane (ADR-0139). Phase 1 serves a throwaway pure-Kotlin
 * baseline ([com.openbank.fraud.infrastructure.ml.BaselineFraudModel]) purely in **shadow** — its
 * output is logged, never honoured. The in-process ONNX Runtime adapter is phase-1b behind this same
 * port; keeping it a port means the model engine swaps with no change to the scoring use case.
 *
 * Returns `null` when serving is unavailable (a real ONNX failure) so the caller degrades to
 * rules-only — the ADR-0139 fail-closed floor.
 */
interface MlModelPort {
    /** A risk score in `[0.0, 1.0]` for the given feature vector, or `null` if serving is unavailable. */
    fun scoreShadow(features: Map<String, Double>): Double?
}
