// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

/** Default config: the customer channel is OFF and advertises nothing (ADR-0383 exposure order). */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpCustomerChannelOffIT {
    @Test
    fun `protected-resource metadata is 404 while the channel is off`() {
        given().get("/.well-known/oauth-protected-resource/mcp").then().statusCode(404)
        given().get("/.well-known/oauth-protected-resource").then().statusCode(404)
    }
}
