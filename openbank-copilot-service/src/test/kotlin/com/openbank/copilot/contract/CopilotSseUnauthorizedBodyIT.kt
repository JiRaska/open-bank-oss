// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt in the repository root for details.

package com.openbank.copilot.contract

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class CopilotSseUnauthorizedBodyIT {
    @Test
    fun `missing bearer on SSE endpoint returns JSON error`() {
        given()
            .contentType("application/json")
            .body("""{"message":"hello"}""")
            .post("/api/v1/copilot/chat/stream")
            .then()
            .statusCode(401)
            .contentType("application/json")
            .body("status", equalTo(401))
            .body("code", equalTo("UNAUTHORIZED"))
    }
}
