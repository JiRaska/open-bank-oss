// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import javax.sql.DataSource

abstract class StandaloneCatalogBootContract {
    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "quarkus.oidc.tls.verification")
    lateinit var oidcTlsVerification: String

    protected fun assertNoBankCompatibilityData() {
        assertThat(ConfigProvider.getConfig().getValue("quarkus.kafka.devservices.enabled", String::class.java))
            .isEqualTo("false")
        assertThat(count("products")).isZero()
        assertThat(count("bank_v1_product_mapping")).isZero()
        assertThat(oidcTlsVerification).isEqualTo("required")
    }

    private fun count(table: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }
}

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_standalone_empty")],
)
@TestProfile(EmptyStandaloneProfile::class)
@TestSecurity(user = "standalone-operator", roles = ["ROLE_OPERATOR"])
class EmptyStandaloneCatalogBootTest : StandaloneCatalogBootContract() {
    @Test
    fun `empty standalone deployment starts without banking or industry packs`() {
        given().get("/api/v2/product-types").then()
            .statusCode(200)
            .body("size()", equalTo(0))
        given().contentType("application/json")
            .body("""{"code":"BANK_ONLY","name":"Bank only","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products").then()
            .statusCode(404)
        given().get("/api/v1/fees").then().statusCode(404)
        assertNoBankCompatibilityData()
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `disabled v1 documented 404s conform to the published ApiError alternative`() {
        val spec = Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
            .use { Yaml().load<Map<String, Any?>>(it) }
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val schemas = ((spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>)
        val apiError = schemas.getValue("ApiError")
        val required = apiError["required"] as List<String>
        val properties = apiError["properties"] as Map<String, Any?>
        val documented404s = listOf(
            Triple("get", "/api/v1/products/{id}", "/api/v1/products/does-not-exist"),
            Triple("put", "/api/v1/products/{id}", "/api/v1/products/does-not-exist"),
            Triple("get", "/api/v1/products/by-code/{code}", "/api/v1/products/by-code/DOES_NOT_EXIST"),
            Triple("post", "/api/v1/products/{id}/activate", "/api/v1/products/does-not-exist/activate"),
            Triple("post", "/api/v1/products/{id}/deactivate", "/api/v1/products/does-not-exist/deactivate"),
            Triple("get", "/api/v1/products/{id}/fees", "/api/v1/products/does-not-exist/fees"),
        )
        documented404s.forEach { (method, specPath, requestPath) ->
            val response = when (method) {
                "get" -> given().get(requestPath)
                "post" -> given().post(requestPath)
                else -> given().contentType("application/json")
                    .body("""{"code":"TEST_PROD","name":"Test","type":"SAVINGS","currency":"EUR"}""")
                    .put(requestPath)
            }.then().statusCode(404)
            val body = response.extract().jsonPath().getMap<String, Any?>("")
            val operation = paths.getValue(specPath)[method] as Map<String, Any?>
            val responses = operation["responses"] as Map<String, Map<String, Any?>>
            val content = responses.getValue("404")["content"] as Map<String, Map<String, Any?>>
            val json = content.getValue("application/json")["schema"] as Map<String, Any?>
            val refs = (json["oneOf"] as List<Map<String, String>>).map { it.getValue("\$ref").substringAfterLast('/') }

            assertThat(refs).describedAs("%s %s", method, specPath)
                .containsExactlyInAnyOrder("LegacyError", "ApiError")
            assertThat(body.keys).describedAs("%s %s body", method, requestPath)
                .containsAll(required).doesNotContain("error")
            assertThat(body.keys).allMatch(properties::containsKey)
            assertThat(body["status"]).isEqualTo(404)
            assertThat(body["code"]).isEqualTo("NOT_FOUND")
        }
    }
}

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_standalone_insurance")],
)
@TestProfile(InsuranceStandaloneProfile::class)
@TestSecurity(user = "standalone-operator", roles = ["ROLE_OPERATOR"])
class InsuranceStandaloneCatalogBootTest : StandaloneCatalogBootContract() {
    @Test
    fun `insurance-only standalone deployment starts without bank data`() {
        given().get("/api/v2/product-types").then()
            .statusCode(200)
            .body("size()", equalTo(2))
            .body("id", everyItem(equalTo("org.openbank.insurance.term-life")))
            .body("version", hasItem(1))
            .body("version", hasItem(2))
        assertNoBankCompatibilityData()
    }
}

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_standalone_banking")],
)
@TestProfile(BankingStandaloneProfile::class)
@TestSecurity(user = "standalone-bank-author", roles = [CatalogRoles.READ, CatalogRoles.AUTHOR])
class BankingStandaloneCatalogBootTest {
    @Test
    fun `banking compatibility is an explicit scope-authorized opt in`() {
        given().get("/api/v2/product-types").then()
            .statusCode(200)
            .body("id", hasItem("org.openbank.banking.legacy-product"))
        given().contentType("application/json")
            .body("""{"code":"STANDALONE_BANK","name":"Bank product","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products").then().statusCode(201)
    }

    @Test
    @TestSecurity(user = "unrelated-user", roles = ["unrelated"])
    fun `banking compatibility reads reject an unrelated issuer scope`() {
        given().get("/api/v1/products").then().statusCode(403)
        given().get("/api/v1/fees").then().statusCode(403)
    }
}

class EmptyStandaloneProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "standalone"

    override fun getConfigOverrides(): Map<String, String> = standaloneOverrides("")
}

class InsuranceStandaloneProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "standalone"

    override fun getConfigOverrides(): Map<String, String> = standaloneOverrides("insurance")
}

class BankingStandaloneProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "standalone"

    override fun getConfigOverrides(): Map<String, String> = standaloneOverrides("banking") +
        ("openbank.catalog.bank-v1-compatibility-enabled" to "true")
}

private fun standaloneOverrides(packs: String): Map<String, String> = mapOf(
    "openbank.catalog.packs" to packs,
    "openbank.catalog.bank-v1-compatibility-enabled" to "false",
    "quarkus.oidc.enabled" to "false",
    "quarkus.oidc.auth-server-url" to "https://issuer.example.test",
    "quarkus.oidc.tls.verification" to "required",
    "authz.enforce" to "false",
)
