// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The customer notification-center journey over the real HTTP surface: notifications land in
 * the store (the Kafka write path is proven by `NotificationConsumerIT`; this journey seeds the
 * same table through plain JDBC, the shape every repository IT here uses), and a caller then
 * walks the operator-visible route sequence — party list with unread counts, single read,
 * party-scoped self read (with the IDOR guard), mark-read idempotence, read-all, push
 * preferences, device registration without token leakage, deactivation, and the Tier A
 * break-glass dispatch control with its four-eyes resume.
 *
 * The module's ITs prove one property each; this proves the route sequence serves the whole
 * "customer opens the app during an incident" story, so a wiring regression that deregisters a
 * route (#3371), drops the party scoping from the self read, or breaks the four-eyes resume
 * fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(CustomerNotificationCenterJourneyE2E.AuthzOffProfile::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerNotificationCenterJourneyE2E {

    /** `authz.enforce` defaults to true; with no OPA sidecar in a test JVM the advisory PEP is off. */
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "false")
    }

    @Inject
    lateinit var dataSource: DataSource

    private val partyA = UUID.randomUUID()
    private val partyB = UUID.randomUUID()
    private val notificationIds = (1..4).map { UUID.randomUUID() }
    private var deviceId: String? = null
    private var proposalId: String? = null

    private fun post(path: String, body: String): Response = given()
        .contentType("application/json")
        .body(body)
        .post(path)
        .then()
        .extract()
        .response()

    private fun insert(notificationId: UUID, partyId: UUID, createdAt: String, readAt: String?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO notifications
                    (id, notification_id, party_id, channel, template, recipient, body, status, created_at, read_at)
                    VALUES ((SELECT COALESCE(MAX(id), 0) + 1000000 FROM notifications), ?, ?, 'EMAIL', 'TEST', ?, 'test body', 'SENT', ?, ?)""",
            ).use { statement ->
                statement.setObject(1, notificationId)
                statement.setObject(2, partyId)
                statement.setString(3, "$partyId@example.com")
                statement.setTimestamp(4, Timestamp.from(Instant.parse(createdAt)))
                statement.setTimestamp(5, readAt?.let { Timestamp.from(Instant.parse(it)) })
                statement.executeUpdate()
            }
        }
    }

    private fun seedNotifications() {
        insert(notificationIds[0], partyA, "2026-10-07T08:00:00Z", null)
        insert(notificationIds[1], partyA, "2026-10-07T08:01:00Z", null)
        insert(notificationIds[2], partyA, "2026-10-07T08:02:00Z", "2026-10-07T08:30:00Z")
        insert(notificationIds[3], partyB, "2026-10-07T08:03:00Z", null)
    }

    @Test
    @Order(1)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `1 - the party list shows newest first with an exact unread count`() {
        seedNotifications()

        given().queryParam("partyId", partyA).queryParam("size", 10).get("/api/v1/notifications").then()
            .statusCode(200)
            .body("total", equalTo(3))
            .body("unreadCount", equalTo(2))
            .body("items", hasSize<Any>(3))
            .body("items[0].id", equalTo(notificationIds[2].toString()))
            .body("items[1].id", equalTo(notificationIds[1].toString()))
            .body("items[2].id", equalTo(notificationIds[0].toString()))
            .body("items[0].status", equalTo("SENT"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `2 - a single notification reads back by id and an unknown id is a 404`() {
        given().get("/api/v1/notifications/${notificationIds[0]}").then()
            .statusCode(200)
            .body("id", equalTo(notificationIds[0].toString()))
            .body("partyId", equalTo(partyA.toString()))
            .body("template", equalTo("TEST"))
            .body("body", equalTo("test body"))

        given().get("/api/v1/notifications/${UUID.randomUUID()}").then().statusCode(404)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `3 - the self read is party scoped and answers another party 404`() {
        given().queryParam("partyId", partyA).get("/api/v1/notifications/${notificationIds[0]}/self").then()
            .statusCode(200)
            .body("id", equalTo(notificationIds[0].toString()))

        given().queryParam("partyId", partyB).get("/api/v1/notifications/${notificationIds[0]}/self").then()
            .statusCode(404)
            .body("code", equalTo("NOT_FOUND"))

        given().get("/api/v1/notifications/${notificationIds[0]}/self").then()
            .statusCode(400)
            .body("code", equalTo("BAD_REQUEST"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `4 - mark read flips one row, is idempotent, and never crosses parties`() {
        given().queryParam("partyId", partyA).patch("/api/v1/notifications/${notificationIds[1]}/read").then()
            .statusCode(204)
        given().queryParam("partyId", partyA).patch("/api/v1/notifications/${notificationIds[1]}/read").then()
            .statusCode(204)

        given().queryParam("partyId", partyA).get("/api/v1/notifications").then()
            .statusCode(200)
            .body("unreadCount", equalTo(1))

        given().queryParam("partyId", partyA).patch("/api/v1/notifications/${notificationIds[3]}/read").then()
            .statusCode(404)
            .body("code", equalTo("NOT_FOUND"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `5 - read all flips the remaining unread rows for the party`() {
        given().queryParam("partyId", partyA).patch("/api/v1/notifications/read-all").then()
            .statusCode(200)
            .body("marked", equalTo(1))

        given().queryParam("partyId", partyA).get("/api/v1/notifications").then()
            .statusCode(200)
            .body("unreadCount", equalTo(0))

        // Party B's row was untouched by party A's read-all.
        given().queryParam("partyId", partyB).get("/api/v1/notifications").then()
            .statusCode(200)
            .body("unreadCount", equalTo(1))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `6 - push preferences default to all-on and round trip an update`() {
        val unset = UUID.randomUUID()
        given().get("/api/v1/preferences/party/$unset").then()
            .statusCode(200)
            .body("paymentsPush", equalTo(true))
            .body("productPush", equalTo(true))
            .body("marketingPush", equalTo(true))

        given()
            .contentType("application/json")
            .body("""{"paymentsPush":false,"productPush":true,"marketingPush":false}""")
            .put("/api/v1/preferences/party/$partyA").then()
            .statusCode(200)
            .body("paymentsPush", equalTo(false))
            .body("productPush", equalTo(true))

        given().get("/api/v1/preferences/party/$partyA").then()
            .statusCode(200)
            .body("paymentsPush", equalTo(false))
            .body("productPush", equalTo(true))
            .body("marketingPush", equalTo(false))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `7 - device registration upserts and the list never leaks the provider token`() {
        val first = post(
            "/api/v1/devices",
            """{"partyId":"$partyA","platform":"FCM","token":"provider-token-1","appInstance":"phone-1"}""",
        )
        first.then().statusCode(201)
        deviceId = first.path("id")

        // Re-registering the same token moves it (new app instance); the party still has one device.
        post(
            "/api/v1/devices",
            """{"partyId":"$partyA","platform":"FCM","token":"provider-token-1","appInstance":"phone-2"}""",
        ).then().statusCode(201)

        given().queryParam("partyId", partyA).get("/api/v1/devices").then()
            .statusCode(200)
            .body("total", equalTo(1))
            .body("items", hasSize<Any>(1))
            .body("items[0].token", nullValue())
            .body("items[0].platform", equalTo("FCM"))
            .body("items[0].appInstance", equalTo("phone-2"))

        post("/api/v1/devices", """{"partyId":"$partyA","platform":"NOPE","token":"t","appInstance":"x"}""").then()
            .statusCode(400)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "app", roles = ["ROLE_API"])
    fun `8 - deactivating a device retires it and a replay is a 404`() {
        given().queryParam("partyId", partyA).delete("/api/v1/devices/$deviceId").then().statusCode(204)

        // The registry keeps the row as history; what changes is the lifecycle status.
        given().queryParam("partyId", partyA).get("/api/v1/devices").then()
            .statusCode(200)
            .body("total", equalTo(1))
            .body("items[0].status", equalTo("INACTIVE"))

        given().queryParam("partyId", partyA).delete("/api/v1/devices/$deviceId").then()
            .statusCode(404)
            .body("message", equalTo("Device not found or already inactive"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "sre-1", roles = ["ROLE_OPERATOR"])
    fun `9 - break glass halts dispatch and a resume proposal waits for another actor`() {
        given().get("/api/v1/ops/dispatch").then()
            .statusCode(200)
            .body("current.state", equalTo("ENABLED"))

        post("/api/v1/ops/dispatch/halt", """{"reason":"provider outage"}""").then()
            .statusCode(200)
            .body("state", equalTo("HALTED"))

        val proposal = post("/api/v1/ops/dispatch/resume/propose", """{"reason":"provider restored"}""")
        proposal.then().statusCode(202)
        proposalId = proposal.path("proposalId")

        // The proposer cannot approve their own proposal (maker-checker).
        post("/api/v1/ops/dispatch/resume/$proposalId/approve", """{"reason":"ok"}""").then()
            .statusCode(422)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "sre-2", roles = ["ROLE_OPERATOR"])
    fun `10 - a different actor approves the resume and dispatch runs again`() {
        post("/api/v1/ops/dispatch/resume/$proposalId/approve", """{"reason":"verified"}""").then()
            .statusCode(200)
            .body("state", equalTo("ENABLED"))

        given().get("/api/v1/ops/dispatch").then()
            .statusCode(200)
            .body("current.state", equalTo("ENABLED"))
    }

    @Test
    @Order(11)
    @TestSecurity(user = "viewer-1", roles = ["ROLE_VIEWER"])
    fun `11 - a viewer can list notifications but cannot pull break-glass`() {
        given().queryParam("partyId", partyA).get("/api/v1/notifications").then().statusCode(200)
        post("/api/v1/ops/dispatch/halt", """{"reason":"nope"}""").then().statusCode(403)
    }
}
