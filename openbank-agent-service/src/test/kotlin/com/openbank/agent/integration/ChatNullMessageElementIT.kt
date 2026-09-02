// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test

/**
 * Jackson's Kotlin module null-checks a data class's CONSTRUCTOR PARAMETERS; it does not check the
 * ELEMENTS of a collection. So `{"messages": [null]}` deserialises happily into a `List<ChatTurn>`
 * holding a null, the handler dereferences it, and the endpoint answers 500 where 400 belongs.
 *
 * Only a booted-HTTP test can see this: a unit test constructing `ChatRequest` in Kotlin cannot
 * produce the null element at all.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class ChatNullMessageElementIT {

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a null element in messages is rejected with 400, not 500`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"messages":[null]}""")
            .`when`().post("/agent/chat")
            .then().statusCode(400)
    }
}
