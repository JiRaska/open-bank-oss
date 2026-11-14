// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/**
 * #15723: the 8443 listener (`client-auth: required` in %prod) sits beside the plain port, which
 * must keep serving the existing callers. Production listener shape, real HTTP, both ports:
 * no client certificate is refused at the handshake, a trusted certificate without a bearer is
 * 401 (the certificate is transport authentication, never a login, #12511), and a valid bearer
 * is served identically on both listeners.
 */
@QuarkusTest
@TestProfile(SanctionsListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v3/sanctions/pending"

    class Profile : ProductionListenerProfile()
}
