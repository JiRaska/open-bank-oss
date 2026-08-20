// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.infrastructure.signing.AnchorSignerProducer
import com.openbank.audit.infrastructure.signing.AwsKmsAnchorSigner
import com.openbank.audit.infrastructure.signing.LocalHmacAnchorSigner
import com.openbank.audit.it.PostgresTestResource
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kms.KmsClient
import java.util.Optional

/**
 * Regression coverage for the boot failure #5844 shipped: `kms-key-id` was injected as a plain
 * `String` with `defaultValue = ""`, and `application.yaml` binds `${AUDIT_ANCHOR_KMS_KEY_ID:}`,
 * so the property is DEFINED as empty. SmallRye reads an empty value as no value, injection threw
 * `SRCFG00040`, and the whole Quarkus deployment aborted — `main` stayed red because every
 * `@QuarkusTest` in the module then reports as SKIPPED rather than FAILED.
 *
 * The first test must be a real `@QuarkusTest`: the defect lives in CDI injection, so a unit test
 * that calls the producer directly cannot see it. The other two cover the `require` that the old
 * shape made unreachable dead code — injection threw before the method body ever ran.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AnchorSignerBootIT {

    @Inject
    lateinit var signer: AnchorSigner

    @Test
    fun `boots with no kms-key-id configured and produces the default hmac signer`() {
        assertThat(signer).isInstanceOf(LocalHmacAnchorSigner::class.java)
    }

    @Test
    fun `signer=kms with no key fails with the intended message, not SRCFG00040`() {
        assertThatThrownBy {
            AnchorSignerProducer().select("kms", "irrelevant", Optional.empty()) {
                error("the KMS client must not be built when the key id is missing")
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("openbank.audit.anchor.kms-key-id is required when signer=kms")

        // An empty/blank value is the same case: application.yaml's `${AUDIT_ANCHOR_KMS_KEY_ID:}`
        // yields "" rather than an absent Optional wherever the env var is set but empty.
        assertThatThrownBy {
            AnchorSignerProducer().select("kms", "irrelevant", Optional.of("  ")) {
                error("the KMS client must not be built when the key id is blank")
            }
        }.hasMessage("openbank.audit.anchor.kms-key-id is required when signer=kms")
    }

    @Test
    fun `signer=kms with a key produces the KMS signer`() {
        val produced = AnchorSignerProducer()
            .select("kms", "irrelevant", Optional.of("alias/openbank-audit-anchor")) { mockk<KmsClient>() }

        assertThat(produced).isInstanceOf(AwsKmsAnchorSigner::class.java)
        assertThat(produced.keyId).isEqualTo("alias/openbank-audit-anchor")
    }
}
