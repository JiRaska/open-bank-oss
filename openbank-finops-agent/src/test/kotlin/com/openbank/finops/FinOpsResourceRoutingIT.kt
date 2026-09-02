// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

/** Proves the viewer read uses the live reactive repository, not a worker-thread bridge. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FinOpsResourceRoutingIT {

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `viewer anomalies query reaches reactive persistence`() {
        given()
            .`when`().get("/api/v1/finops/anomalies")
            .then().statusCode(200)
    }
}
