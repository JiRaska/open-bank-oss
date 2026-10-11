// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.restassured.RestAssured.given
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Real Keycloak client_credentials tokens, enforced policy, HTTP resource and Postgres state. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(AccountPensionOidcTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(AccountPensionOwnershipOidcProfile::class)
class AccountPensionOwnershipOidcIT {
    @Inject
    lateinit var accounts: AccountRepository

    @Inject
    lateinit var vertx: Vertx

    @ConfigProperty(name = "openbank.test.pension.issuer")
    lateinit var issuer: String

    @ConfigProperty(name = "openbank.test.pension.secret")
    lateinit var secret: String

    @ConfigProperty(name = "openbank.test.pension.policy-url")
    lateinit var policyUrl: String

    @Test
    fun `only pension M2M token verifies real active ownership`() {
        val owner = UUID.randomUUID()
        val foreign = UUID.randomUUID()
        val activeId = UUID.randomUUID()
        val frozenId = UUID.randomUUID()
        val activeIban = "CZ6508000000192000145399"
        val frozenIban = "CZ3808000000192000145400"
        onVertx {
            accounts.save(account(activeId, activeIban, owner, AccountStatus.ACTIVE))
            accounts.save(account(frozenId, frozenIban, owner, AccountStatus.FROZEN))
        }
        val pension = token("openbank-pension")
        val unrelated = token("unrelated-service")
        val overgranted = token("overgranted-service")
        check(ConfigProvider.getConfig().getValue("authz.enforce", Boolean::class.java))
        for ((client, token) in listOf(
            "openbank-pension" to pension,
            "unrelated-service" to unrelated,
            "overgranted-service" to overgranted,
        )) {
            val payload = ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split('.')[1]))
            check(payload.path("azp").asText() == client)
            check(payload.path("preferred_username").asText() == "service-account-$client")
        }
        val path = "/api/v1/accounts/ownership-verifications"
        val owned = request(activeIban, owner)

        given().contentType("application/json").body(owned).post(path).then().statusCode(401)
        given().auth().oauth2(unrelated).contentType("application/json").body(owned)
            .post(path).then().statusCode(403)
        // This token deliberately has the special realm role; enforced policy still binds the client identity.
        given().auth().oauth2(overgranted).contentType("application/json").body(owned)
            .post(path).then().statusCode(403)
        given().auth().oauth2(pension).contentType("application/json").body(owned)
            .post(path).then().statusCode(200)
            .body("owned", equalTo(true), "active", equalTo(true), "accountId", equalTo(activeId.toString()))
        given().auth().oauth2(pension).contentType("application/json").body(request(activeIban, foreign))
            .post(path).then().statusCode(200)
            .body("owned", equalTo(false), "active", equalTo(false), "accountId", nullValue())
        given().auth().oauth2(pension).contentType("application/json").body(request(frozenIban, owner))
            .post(path).then().statusCode(200)
            .body("owned", equalTo(true), "active", equalTo(false), "accountId", nullValue())
        given().auth().oauth2(pension).contentType("application/json")
            .body(request("CZ0708000000000000000099", owner))
            .post(path).then().statusCode(200)
            .body("owned", equalTo(false), "active", equalTo(false), "accountId", nullValue())
        val decisions = given().get("$policyUrl/__decisions").then().statusCode(200)
            .extract().body().`as`(Array<String>::class.java).toList()
        assertThat(decisions).contains("service-account-overgranted-service:false")
        assertThat(decisions).contains("service-account-openbank-pension:true")
    }

    private fun token(client: String): String = given().contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", client)
        .formParam("client_secret", secret)
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200)
        .extract().path("access_token")

    private fun request(iban: String, party: UUID) = """{"iban":"$iban","partyId":"$party"}"""

    private fun account(id: UUID, iban: String, owner: UUID, status: AccountStatus) = Account(
        id = id,
        accountNumber = Iban(iban),
        accountType = AccountType.CURRENT,
        partyId = owner,
        productId = UUID.randomUUID(),
        currency = CurrencyCode("CZK"),
        status = status,
        openedAt = Instant.parse("2026-01-01T00:00:00Z"),
        closedAt = null,
        version = 0,
    )

    /** Reactive Panache requires a Vert.x context; an HTTP read alone cannot seed DB state. */
    private fun onVertx(block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val context = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(context, true)
        val dispatcher = Executor { command -> context.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        future.get(10, TimeUnit.SECONDS)
    }
}

class AccountPensionOwnershipOidcProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.oidc.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "true",
        "authz.enforce" to "true",
        "test.authz.real-opa" to "true",
    )
}
