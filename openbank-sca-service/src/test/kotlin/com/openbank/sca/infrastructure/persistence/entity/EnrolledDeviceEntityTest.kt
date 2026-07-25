// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.SignatureAlgorithm
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The enrolled-device entity mapping was 0% covered. `fromDomain`/`toDomain` is the only place the
 * device public key or credential id can be silently dropped on a DB round-trip — and a dropped
 * public key means a valid device assertion can no longer be verified. Pure mapping, no DB.
 */
class EnrolledDeviceEntityTest {

    @Test
    fun `device survives a fromDomain-toDomain round trip`() {
        val device = EnrolledDevice(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            credentialId = "cred-xyz",
            publicKeySpkiB64 = "MFkwEwYHKoZIzj0CAQ...",
            algorithm = SignatureAlgorithm.ES256,
            createdAt = OffsetDateTime.parse("2026-07-25T08:00:00Z"),
        )

        val restored = EnrolledDeviceEntity.fromDomain(device).toDomain()

        assertThat(restored).isEqualTo(device)
    }

    @Test
    fun `fromDomain copies each column`() {
        val device = EnrolledDevice(
            partyId = UUID.randomUUID(),
            credentialId = "cred-1",
            publicKeySpkiB64 = "spki",
            algorithm = SignatureAlgorithm.ED25519,
            createdAt = OffsetDateTime.parse("2026-07-25T08:00:00Z"),
        )

        val entity = EnrolledDeviceEntity.fromDomain(device)

        assertThat(entity.id).isEqualTo(device.id)
        assertThat(entity.partyId).isEqualTo(device.partyId)
        assertThat(entity.credentialId).isEqualTo("cred-1")
        assertThat(entity.publicKeySpki).isEqualTo("spki")
        assertThat(entity.algorithm).isEqualTo(SignatureAlgorithm.ED25519)
        assertThat(entity.createdAt).isEqualTo(device.createdAt)
    }
}
