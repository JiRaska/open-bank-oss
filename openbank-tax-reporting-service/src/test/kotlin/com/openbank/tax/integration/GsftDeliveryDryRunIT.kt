// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.tax.application.port.out.GsftDataGap
import com.openbank.tax.application.port.out.GsftSnapshot
import com.openbank.tax.application.port.out.GsftSnapshotPort
import com.openbank.tax.domain.gsft.DepositAccount
import com.openbank.tax.domain.gsft.Depositor
import com.openbank.tax.domain.gsft.DepositorIdentity
import com.openbank.tax.domain.gsft.EntitledPersonType
import com.openbank.tax.domain.gsft.FxRates
import com.openbank.tax.domain.gsft.Holding
import com.openbank.tax.domain.gsft.SpecialRegimeShare
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * #15356 TEST dry run of the GSFT depositor delivery, end to end over real HTTP, CDI, OPA wiring,
 * Flyway and Postgres: a complete fixture snapshot (single, joint, FX, over-limit and
 * special-regime depositors plus a beneficiary) is assembled in TEST mode, approved by a SECOND
 * person, delivered (decrypted here with the throwaway private key and unzipped) and acknowledged.
 * The production snapshot always has gaps; a snapshot with gaps is refused with the gap list.
 *
 * Two actors need two `@TestSecurity` methods, so the lifecycle is split over ordered methods that
 * share the return id.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_gsft_it")],
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@TestProfile(GsftDeliveryDryRunIT.GsftProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GsftDeliveryDryRunIT {
    class GsftProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.gsft.bank-code" to BANK,
            "openbank.gsft.pgp-public-key" to TestKey.publicArmored(),
            "openbank.statutory-returns.deadline-check-every" to "1h",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FixtureSnapshots::class.java)
    }

    /** Complete, verifiable depositor data — what production cannot source yet. */
    @Alternative
    @ApplicationScoped
    class FixtureSnapshots : GsftSnapshotPort {
        override suspend fun snapshot(relevantDay: LocalDate): GsftSnapshot = if (relevantDay == GAPPED_DAY) {
            GsftSnapshot(
                relevantDay,
                emptyList(),
                emptyList(),
                emptyList(),
                null,
                GsftDataGap.entries.toSet(),
                specialShares = emptyList(),
                qualifyingCredits = emptyList(),
            )
        } else {
            complete(relevantDay)
        }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `a snapshot with unsourced data is refused with every gap named`() {
        val body = assemble(TEST_CODE, GAPPED_DAY).then().statusCode(503).extract().path<String>("error")
        GsftDataGap.entries.forEach { assertThat(body).contains(it.name) }
        assertThat(count("select count(*) from statutory_return")).isZero()
        assertThat(count("select count(*) from gsft_package")).isZero()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `TEST assembly renders the exact batch and attests every file by hash`() {
        val response = assemble(TEST_CODE, DAY).then().statusCode(200)
            .body("status", equalTo("ASSEMBLED"))
            .body("periodicity", equalTo("DAY"))
            .body("period", equalTo("2026-11-02"))
            // 3 working days after Monday 2026-11-02.
            .body("dueDate", equalTo("2026-11-05"))
            .extract()
        id = response.path("id")
        val created = BigDecimal(response.path<Any>("datapoints.CREATED_AT_EPOCH_S").toString()).toLong()
        val datapoints: Map<String, Any> = response.path("datapoints")
        assertThat(datapoints.mapValues { BigDecimal(it.value.toString()) }.filterKeys { it != "CREATED_AT_EPOCH_S" })
            .usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal::class.java)
            .isEqualTo(EXPECTED_DATAPOINTS)

        val lines = lines(id)
        val files = lines.filter { it["kind"] == "FILE" }
        assertThat(files.map { it["name"] })
            .containsExactly("DIW$BANK.00001", "DIC$BANK.00001", "DIA$BANK.00001", "DIL$BANK.00001", "DII$BANK.00001")
        val archive = lines.single { it["kind"] == "ARCHIVE" }
        assertThat(archive["name"]).isEqualTo("GSFT$BANK.00001.zip")

        // The stored archive is exactly what the lines attest, entry by entry.
        val stored = storedArchive(archive.getValue("sha256"))
        assertThat(sha256(stored)).isEqualTo(archive["sha256"])
        assertThat(archive["size"]).isEqualTo(stored.size.toString())
        val entries = unzip(stored)
        assertThat(entries.keys.toList()).isEqualTo(files.map { it["name"] })
        files.forEach { line ->
            val bytes = entries.getValue(line.getValue("name"))
            assertThat(sha256(bytes)).describedAs(line["name"]).isEqualTo(line["sha256"])
            assertThat(line["size"]).isEqualTo(bytes.size.toString())
        }
        val timestamp = LocalDateTime.ofInstant(Instant.ofEpochSecond(created), ZoneId.of("Europe/Prague"))
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        assertThat(entries.getValue("DIW$BANK.00001")).isEqualTo(
            "R,$timestamp,00001,000000006,000000006,000000002,000000001,000000000,000000000,000000327500000,1,TEST\r\n"
                .toByteArray(CP1250),
        )
        assertThat(String(entries.getValue("DII$BANK.00001"), CP1250))
            .isEqualTo("D,9001010008,F,00001,CZ6508000000000000000005,CZK,000000040000000,000000040000000\r\n")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `the assembler cannot approve, and nothing is delivered before approval`() {
        post("/$id/approve", "{}").then().statusCode(409).body("error", containsString("Four-eyes"))
        post("/$id/gsft-delivery", """{"reference":"GSFT-T-1"}""").then().statusCode(409)
            .body("error", containsString("not APPROVED"))
        // The generic route cannot file a GSFT return without the package check.
        post("/$id/submitted", """{"reference":"GSFT-T-1"}""").then().statusCode(409)
            .body("error", containsString("gsft-delivery"))
        status(id, "ASSEMBLED")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bob", roles = ["ROLE_OPERATOR"])
    fun `a second person approves, a tampered archive is refused, the attested one is delivered encrypted`() {
        post("/$id/approve", "{}").then().statusCode(200)
            .body("status", equalTo("APPROVED")).body("approvedBy", equalTo("bob"))

        // Archive intact, but an attested FILE hash no longer matches its entry: refused at entry level.
        val lineIndex = 1
        val attestedLine = lineJson(id, lineIndex)
        setLineJson(
            id,
            lineIndex,
            attestedLine.replace(Regex("\"sha256\":\"[0-9a-f]{64}\""), "\"sha256\":\"${"0".repeat(64)}\""),
        )
        post("/$id/gsft-delivery", """{"reference":"GSFT-T-1"}""").then().statusCode(409)
            .body("error", containsString("files do not match the attested files"))
        setLineJson(id, lineIndex, attestedLine)
        status(id, "APPROVED")

        val sha = lines(id).single { it["kind"] == "ARCHIVE" }.getValue("sha256")
        val original = storedArchive(sha)
        val tampered = original.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        updateArchive(sha, tampered)
        post("/$id/gsft-delivery", """{"reference":"GSFT-T-1"}""").then().statusCode(409)
            .body("error", containsString("does not match the attestation"))
        status(id, "APPROVED")
        updateArchive(sha, original)

        val delivered = post("/$id/gsft-delivery", """{"reference":"GSFT-T-1"}""").then().statusCode(200)
            .body("statutoryReturn.status", equalTo("SUBMITTED"))
            .body("statutoryReturn.submissionReference", equalTo("GSFT-T-1"))
            .body("fileName", equalTo("GSFT$BANK.00001.zip.pgp"))
            .extract()
        val (name, clear) = decrypt(Base64.getDecoder().decode(delivered.path<String>("encryptedPackage")))
        assertThat(name).isEqualTo("GSFT$BANK.00001.zip")
        assertThat(clear).isEqualTo(original)
        val attested = lines(id).filter { it["kind"] == "FILE" }.associate { it["name"] to it["sha256"] }
        assertThat(unzip(clear).mapValues { sha256(it.value) }).isEqualTo(attested)
        // The artefact that left is persisted, and can be fetched again until GSFT answers.
        val encrypted = Base64.getDecoder().decode(delivered.path<String>("encryptedPackage"))
        assertThat(storedDelivery(id)).isEqualTo(encrypted)
        val again = post("/$id/gsft-delivery", """{"reference":"GSFT-T-1"}""").then().statusCode(200)
            .body("statutoryReturn.status", equalTo("SUBMITTED")).extract().path<String>("encryptedPackage")
        assertThat(Base64.getDecoder().decode(again)).isEqualTo(encrypted)
        post("/$id/gsft-delivery", """{"reference":"GSFT-T-2"}""").then().statusCode(409)
            .body("error", containsString("another reference"))

        post("/$id/acknowledgement", """{"accepted":true,"reference":"GSFT-ACK-1"}""").then().statusCode(200)
            .body("status", equalTo("ACKNOWLEDGED")).body("acknowledgement", equalTo("GSFT-ACK-1"))
        post("/$id/gsft-delivery", """{"reference":"GSFT-T-1"}""").then().statusCode(409)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `KEEN is rendered only for the explicitly named LIVE return, numbered after the TEST batch`() {
        val liveId: String = assemble(LIVE_CODE, DAY).then().statusCode(200)
            .body("returnCode", equalTo(LIVE_CODE)).extract().path("id")
        val sha = lines(liveId).single { it["kind"] == "ARCHIVE" }.getValue("sha256")
        // TEST and LIVE share the bank's batch sequence: the TEST batch was 00001.
        val diw = String(unzip(storedArchive(sha)).getValue("DIW$BANK.00002"), CP1250)
        assertThat(diw).endsWith(",000000327500000,1,KEEN\r\n")
    }

    private fun assemble(code: String, day: LocalDate) = post(
        "/assemble",
        """{"catalogueId":"cz-gsft","returnCode":"$code","entityId":"company","period":"$day"}""",
    )

    private fun post(path: String, body: String) =
        given().contentType(ContentType.JSON).body(body).post("/api/v1/statutory-returns$path")

    private fun status(returnId: String, expected: String) {
        given().get("/api/v1/statutory-returns/$returnId").then().statusCode(200).body("status", equalTo(expected))
    }

    private fun lines(returnId: String): List<Map<String, String>> =
        given().get("/api/v1/statutory-returns/$returnId/lines").then().statusCode(200).extract().path("lines")

    private fun storedArchive(sha: String): ByteArray = jdbc { c ->
        c.prepareStatement("select archive from gsft_package where archive_sha256 = ?").use { s ->
            s.setString(1, sha)
            s.executeQuery().use { rs ->
                check(rs.next()) { "no archive $sha" }
                rs.getBytes(1)
            }
        }
    }

    private fun updateArchive(sha: String, bytes: ByteArray) = jdbc { c ->
        c.prepareStatement("update gsft_package set archive = ? where archive_sha256 = ?").use { s ->
            s.setBytes(1, bytes)
            s.setString(2, sha)
            check(s.executeUpdate() == 1)
        }
    }

    private fun lineJson(returnId: String, index: Int): String = jdbc { c ->
        c.prepareStatement(
            "select fields_json from statutory_return_line where return_id = ?::uuid and line_index = ?",
        ).use { s ->
            s.setString(1, returnId)
            s.setInt(2, index)
            s.executeQuery().use { rs ->
                check(rs.next())
                rs.getString(1)
            }
        }
    }

    private fun setLineJson(returnId: String, index: Int, json: String) = jdbc { c ->
        c.prepareStatement(
            "update statutory_return_line set fields_json = ? where return_id = ?::uuid and line_index = ?",
        ).use { s ->
            s.setString(1, json)
            s.setString(2, returnId)
            s.setInt(3, index)
            check(s.executeUpdate() == 1)
        }
    }

    private fun storedDelivery(returnId: String): ByteArray = jdbc { c ->
        c.prepareStatement("select encrypted from gsft_delivery where return_id = ?::uuid").use { s ->
            s.setString(1, returnId)
            s.executeQuery().use { rs ->
                check(rs.next()) { "no delivery stored for $returnId" }
                rs.getBytes(1)
            }
        }
    }

    private fun count(sql: String): Int = jdbc { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private fun decrypt(message: ByteArray): Pair<String, ByteArray> {
        val list = JcaPGPObjectFactory(message).nextObject() as PGPEncryptedDataList
        val data = list.encryptedDataObjects.next() as PGPPublicKeyEncryptedData
        val factory = JcePublicKeyDataDecryptorFactoryBuilder().setProvider(BC).build(TestKey.privateKey())
        val literal = JcaPGPObjectFactory(data.getDataStream(factory)).nextObject() as PGPLiteralData
        val bytes = literal.inputStream.readBytes()
        assertThat(data.verify()).isTrue()
        return literal.fileName to bytes
    }

    private fun unzip(zip: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.forEach { put(it.name, z.readBytes()) }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    /**
     * A throwaway RSA key generated per JVM run. The profile and the test class load in different
     * classloaders, so the pair is shared through a per-process temp file, never a companion field.
     */
    object TestKey {
        private val dir: Path = Path.of(
            System.getProperty("java.io.tmpdir"),
            "gsft-it-${ProcessHandle.current().pid()}",
        )

        @Synchronized
        fun publicArmored(): String {
            ensure()
            return Files.readString(dir.resolve("public.asc"))
        }

        fun privateKey(): PrivateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(dir.resolve("private.pk8"))))

        private fun ensure() {
            if (Files.exists(dir.resolve("public.asc"))) return
            Files.createDirectories(dir)
            val java = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair()
            val pair = JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, java, Date())
            val sha1 = JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)
            val ring = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pair,
                "gsft-dry-run-recipient",
                sha1,
                null,
                null,
                JcaPGPContentSignerBuilder(pair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(BC),
                JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1).setProvider(BC)
                    .build("x".toCharArray()),
            ).generatePublicKeyRing()
            val out = ByteArrayOutputStream()
            ArmoredOutputStream(out).use { ring.encode(it) }
            Files.write(dir.resolve("private.pk8"), java.private.encoded)
            Files.writeString(dir.resolve("public.asc"), out.toString(Charsets.US_ASCII))
        }
    }

    companion object {
        private const val BANK = "6363"
        private const val TEST_CODE = "GSFT-DIW-TEST"
        private const val LIVE_CODE = "GSFT-DIW-LIVE"
        private const val RSA_BITS = 2048
        private val BC = BouncyCastleProvider()
        private val CP1250: Charset = Charset.forName("windows-1250")
        private val DAY: LocalDate = LocalDate.parse("2026-11-02")
        private val GAPPED_DAY: LocalDate = LocalDate.parse("2026-11-01")

        private lateinit var id: String

        private val single = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        private val jointA = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        private val jointB = UUID.fromString("00000000-0000-0000-0000-00000000000c")
        private val rich = UUID.fromString("00000000-0000-0000-0000-00000000000d")
        private val owner = UUID.fromString("00000000-0000-0000-0000-00000000000e")
        private val beneficiary = UUID.fromString("00000000-0000-0000-0000-00000000000f")

        /**
         * Single 150 000; joint 200 000 split 50/50; jointA also EUR 1 000 at 25.000; rich 3 000 000
         * (capped at 2 500 000); owner's special-regime 400 000 (uncapped) with one beneficiary.
         * T_PAYMENT = 150 000 + 125 000 + 100 000 + 2 500 000 + 400 000 + 0 = 3 275 000.
         */
        private val EXPECTED_DATAPOINTS: Map<String, BigDecimal> = mapOf(
            "N_CLIENT" to 6, "N_ACCOUNT" to 6, "N_COLLECTIVE" to 2, "N_SPECIAL" to 1, "BATCH_NUMBER" to 1,
            "REJECTED_PERSONS" to 0, "EXCLUDED_DEPOSITORS" to 0, "CAPPED_CLAIMS" to 1,
            "SYNTHETIC_DEPOSITORS_DROPPED" to 0, "SYNTHETIC_ACCOUNTS_DROPPED" to 0, "OVERDRAWN_ACCOUNTS_DROPPED" to 0,
            "THB_CLAIMS" to 0, "THB_REFUSED_CREDITS" to 0, "FILES" to 5,
        ).mapValues { BigDecimal(it.value) } + mapOf(
            "T_PAYMENT" to BigDecimal("3275000.00"),
            "THB_COMPENSATION_CZK" to BigDecimal.ZERO,
        )

        private fun person(id: UUID, idc: String, type: EntitledPersonType = EntitledPersonType.NATURAL_PERSON) =
            DepositorIdentity(id, idc, type, "Osoba $idc", "Ulice 1", "Praha", "11000", "CZ", "cs")

        private fun account(
            n: Int,
            currency: String,
            amount: String,
            holders: List<Holding>,
            special: Boolean = false,
        ) = DepositAccount(
            UUID.fromString("00000000-0000-0000-0001-%012d".format(n)),
            "CZ65080000000000000000%02d".format(n),
            currency,
            BigDecimal(amount),
            BigDecimal.ZERO,
            holders,
            synthetic = false,
            specialRegime = special,
        )

        fun complete(day: LocalDate): GsftSnapshot {
            val special = account(5, "CZK", "400000", listOf(Holding(owner, null)), special = true)
            return GsftSnapshot(
                relevantDay = day,
                depositors = listOf(single, jointA, jointB, rich, owner).map { Depositor(it, null, false, true) },
                accounts = listOf(
                    account(1, "CZK", "150000", listOf(Holding(single, null))),
                    account(
                        2,
                        "CZK",
                        "200000",
                        listOf(Holding(jointA, BigDecimal("0.5")), Holding(jointB, BigDecimal("0.5"))),
                    ),
                    account(3, "EUR", "1000", listOf(Holding(jointA, null))),
                    account(4, "CZK", "3000000", listOf(Holding(rich, null))),
                    special,
                ),
                identities = listOf(
                    person(single, "8001010001"),
                    person(jointA, "8001010002"),
                    person(jointB, "8001010003"),
                    person(rich, "12345678", EntitledPersonType.LEGAL_PERSON),
                    person(owner, "87654321", EntitledPersonType.LEGAL_PERSON),
                ),
                rates = FxRates(day, mapOf("EUR" to BigDecimal("25.000"))),
                gaps = emptySet(),
                specialShares = listOf(
                    SpecialRegimeShare(person(beneficiary, "9001010008"), special.accountId, BigDecimal("400000.00")),
                ),
                qualifyingCredits = emptyList(),
            )
        }
    }
}
