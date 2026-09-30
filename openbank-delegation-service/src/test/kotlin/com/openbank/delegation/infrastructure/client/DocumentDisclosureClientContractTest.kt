// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentDisclosureClientContractTest {

    @Test
    fun `export client uses the dedicated disclosure OIDC client`() {
        val filter = DocumentServiceDisclosureRestClient::class.java.getAnnotation(OidcClientFilter::class.java)

        assertThat(filter)
            .describedAs("using the default OIDC client would present the shared backend subject")
            .isNotNull()
        assertThat(filter.value).isEqualTo("disclosure")
    }
}
