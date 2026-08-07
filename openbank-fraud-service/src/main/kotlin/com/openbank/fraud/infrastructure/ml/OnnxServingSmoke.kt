// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.ml

import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H1
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H24
import kotlin.system.exitProcess

private const val SMOKE_H1_VALUE = 3.0
private const val SMOKE_H24_VALUE = 7.0
private const val SCORE_OK_MIN_EXCLUSIVE = 0.0
private const val SCORE_OK_MAX_EXCLUSIVE = 1.0

/**
 * In-image smoke check for the ONNX serving path (#3354).
 *
 * The defect this guards against shipped precisely BECAUSE every green check proved the wrong
 * thing: the JVM unit tests run on a glibc host, so they load `libonnxruntime.so` happily, while
 * the deployed image was musl (alpine) and the native library has never loaded there. A green
 * build said nothing about the image. This main exists to be run INSIDE the built image (java
 * -cp with the fast-jar lib + app classpath, see the workflow below) and to fail non-zero if any
 * stage of the real serving path does not work THERE:
 *
 *  1. the ONNX Runtime native library loads (the musl/glibc failure mode),
 *  2. the bundled model + its ADR-0141 card load through the same companion functions the bean
 *     uses (no reimplementation here that could diverge),
 *  3. one real inference round-trip returns a sane shadow score.
 *
 * It reuses [OnnxFraudModel.loadEnvironment] / [OnnxFraudModel.loadSession] directly rather than
 * constructing the CDI bean, so it runs with no container, no config and no DB — the image needs
 * nothing but its own filesystem.
 *
 * Wired into CI in `.github/workflows/onnx-serving-smoke.yml`, which builds the fraud image from
 * the SAME `Dockerfile.deploy` the deploy pipeline uses and runs this in it on the target
 * platform (linux/arm64) — including a negative-control leg on the musl base that MUST fail, so
 * the gate proves it can go red, not only green.
 */
fun main() {
    val env = OnnxFraudModel.loadEnvironment()
    if (env == null) {
        System.err.println("SMOKE FAIL: OrtEnvironment did not load (native library unavailable in this image)")
        exitProcess(1)
    }

    val session = OnnxFraudModel.loadSession(
        env,
        OnnxFraudModel.MODEL_RESOURCE_PATH,
        OnnxFraudModel.MODEL_CARD_PATH,
        false,
    )
    if (session == null) {
        System.err.println("SMOKE FAIL: model session did not load (model/card verification failed in this image)")
        exitProcess(1)
    }

    val features = mapOf(
        VELOCITY_TXN_COUNT_H1.name to SMOKE_H1_VALUE,
        VELOCITY_TXN_COUNT_H24.name to SMOKE_H24_VALUE,
    )
    val score = OnnxFraudModel().scoreShadow(features)
    if (score == null || score <= SCORE_OK_MIN_EXCLUSIVE || score >= SCORE_OK_MAX_EXCLUSIVE) {
        System.err.println("SMOKE FAIL: inference returned no usable score ($score)")
        exitProcess(1)
    }

    session.close()
    println("SMOKE OK: onnxruntime loaded, model verified, score(H1=3,H24=7)=$score")
}
