// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.ResolutionStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pins the fingerprint format: stored fingerprints must keep matching after a deploy. */
class NostroBreakResolutionFingerprintTest {
    @Test
    fun `fingerprint of fixed inputs is a pinned SHA-256 hex value`() {
        val fingerprint = NostroBreakResolutionFingerprint.of(
            "GB00TEST",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            ResolutionStep.PROPOSED,
            "stale item",
            Actor("checker-1", ActorType.HUMAN),
        )
        assertThat(fingerprint).isEqualTo("f99662812b230ccaa947d9a6761febd5645178567157fcd3244f3cc5c352280c")
    }
}
