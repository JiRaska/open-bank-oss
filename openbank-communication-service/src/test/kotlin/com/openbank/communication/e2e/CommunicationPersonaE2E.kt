// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * E2E proof of the ADR-0285 persona-content chain over real HTTP + real Postgres. This service
 * has no downstream HTTP clients and its publish event transport is deliberately UNWIRED (D5),
 * so the evidence is durable, not wire-based: every test reads back what the domain persisted
 * (`style_version`, `playbook_version`, `golden_set_entry`, `communication_audit_event`) over
 * JDBC, exactly the shape the contract ITs established — extended here into full lifecycles
 * (draft → submit → publish → retire) that no single contract IT covers end to end.
 *
 * `@TestSecurity` is fixed per method, so a flow needing TWO distinct principals (maker vs
 * checker) seeds the persisted `maker` column directly — the same pattern the module's contract
 * ITs use; the guard under test (`maker cannot publish their own …`) is the production check.
 */
@QuarkusTest
@TestProfile(CommunicationE2EProfile::class)
class CommunicationPersonaE2E {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR"])
    fun `draft persists as version 1 and the editor state reflects the unpublished persona`() {
        // uiMessages keys are persona-scoped (UiMessages.validate), so the copy-bearing draft
        // uses the seeded customer-copilot persona; no other test of this suite touches it.
        val key = "customer-copilot"

        val id = Given {
            contentType("application/json")
            body(draftBody(basePublishedVersion = 0, uiMessages = true))
        } When { post("/api/v2/personas/$key/style-versions") } Then {
            statusCode(201)
            body("uiMessages.'cs.status.loading'", equalTo("Hledám."))
        } Extract { path<String>("id") }

        Given { this } When { get("/api/v2/personas/$key/editor-state") } Then {
            statusCode(200)
            body("basePublishedVersion", equalTo(0))
            body("published", equalTo(null))
        }
        val row = styleRow(UUID.fromString(id))
        assertThat(row.first).isEqualTo("DRAFT")
        assertThat(row.second).isEqualTo(EDITOR)
        assertThat(auditTrail(UUID.fromString(id))).containsExactly("STYLE_DRAFTED")
    }

    @Test
    @TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR"])
    fun `a future published base is refused before any row is written`() {
        val key = newPersona("e2e-future-base")

        Given {
            contentType("application/json")
            body(draftBody(basePublishedVersion = 1))
        } When { post("/api/v2/personas/$key/style-versions") } Then {
            statusCode(409)
            body(containsString("current published generation"))
        }
        assertThat(styleCount(key)).isZero()
    }

    @Test
    @TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR"])
    fun `a null ui message value is a 400 naming the offending key`() {
        val key = newPersona("e2e-null-ui")

        Given {
            contentType("application/json")
            body(
                """{"tone":"warm","formality":"informal","formOfAddress":"tykani",
                    "uiMessages":{"cs.status.loading":null},"basePublishedVersion":0}""",
            )
        } When { post("/api/v2/personas/$key/style-versions") } Then {
            statusCode(400)
            body(containsString("uiMessages['cs.status.loading'] must not be null"))
        }
        assertThat(styleCount(key)).isZero()
    }

    @Test
    @TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR", "ROLE_COMMS_APPROVER"])
    fun `the maker cannot submit twice nor publish their own version`() {
        val key = newPersona("e2e-maker-guard")
        val id = draftStyle(key)

        Given { this } When { post("/api/v2/personas/style-versions/$id/submit") } Then {
            statusCode(200)
            body("status", equalTo("IN_REVIEW"))
        }
        Given { this } When { post("/api/v2/personas/style-versions/$id/submit") } Then {
            statusCode(409)
            body(containsString("not a draft"))
        }
        Given { this } When { post("/api/v2/personas/style-versions/$id/publish") } Then {
            statusCode(409)
            body(containsString("maker cannot publish their own style version"))
        }
        assertThat(styleRow(id).first).isEqualTo("IN_REVIEW")
        assertThat(auditTrail(id)).containsExactly("STYLE_DRAFTED", "STYLE_SUBMITTED")
    }

    @Test
    @TestSecurity(user = APPROVER, roles = ["ROLE_COMMS_APPROVER"])
    fun `a different checker publishes and the unwired event is recorded loud not silent`() {
        val key = newPersona("e2e-publish")
        val id = UUID.randomUUID()
        seedStyle(id, personaId(key), 1, "IN_REVIEW", maker = "other-editor@openbank.test")

        Given { this } When { post("/api/v2/personas/style-versions/$id/publish") } Then {
            statusCode(200)
            body("status", equalTo("PUBLISHED"))
        }
        Given { this } When { get("/api/v1/personas/$key/published") } Then {
            statusCode(200)
            body("personaKey", equalTo(key))
            body("tone", equalTo("warm"))
            body("styleVersion", equalTo(1))
        }
        val row = styleRow(id)
        assertThat(row.first).isEqualTo("PUBLISHED")
        assertThat(row.third).isEqualTo(APPROVER)
        // ADR-0285 D5: the persona.published transport is not wired — the publish SUCCEEDS and
        // the dropped event lands in the audit trail, never a silent no-op.
        assertThat(auditTrail(id)).contains("STYLE_PUBLISHED", "EVENT_NOT_PUBLISHED")
    }

    @Test
    @TestSecurity(user = APPROVER, roles = ["ROLE_COMMS_APPROVER"])
    fun `publishing a newer version retires the previous one under the one-published invariant`() {
        val key = newPersona("e2e-generations")
        val personaId = personaId(key)
        seedStyle(
            UUID.randomUUID(),
            personaId,
            1,
            "PUBLISHED",
            maker = "gen-one@openbank.test",
            basePublished = 0,
            decidedBy = "gen-one@openbank.test",
        )
        val v2 = UUID.randomUUID()
        seedStyle(v2, personaId, 2, "IN_REVIEW", maker = "gen-two@openbank.test", basePublished = 1)

        Given { this } When { post("/api/v2/personas/style-versions/$v2/publish") } Then {
            statusCode(200)
            body("status", equalTo("PUBLISHED"))
        }
        assertThat(publishedCount(personaId)).isEqualTo(1)
        Given { this } When { get("/api/v2/personas/$key/editor-state") } Then {
            statusCode(200)
            body("basePublishedVersion", equalTo(2))
            body("published.styleVersion", equalTo(2))
        }
    }

    @Test
    @TestSecurity(user = APPROVER, roles = ["ROLE_COMMS_APPROVER"])
    fun `retire takes the style off the published surface`() {
        val key = newPersona("e2e-retire")
        val id = UUID.randomUUID()
        seedStyle(
            id,
            personaId(key),
            1,
            "PUBLISHED",
            maker = "retire-maker@openbank.test",
            basePublished = 0,
            decidedBy = APPROVER,
        )

        Given { this } When { post("/api/v2/personas/style-versions/$id/retire") } Then {
            statusCode(200)
            body("status", equalTo("RETIRED"))
        }
        Given { this } When { get("/api/v1/personas/$key/published") } Then { statusCode(404) }
        assertThat(styleRow(id).first).isEqualTo("RETIRED")
        assertThat(auditTrail(id)).contains("STYLE_RETIRED")
    }

    @Test
    @TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR", "ROLE_COMMS_APPROVER", "ROLE_API"])
    fun `playbook follows the same maker cannot publish guard as the style chain`() {
        val key = newPersona("e2e-playbook-guard")
        val id = Given {
            contentType("application/json")
            body(PLAYBOOK_BODY)
        } When { post("/api/v1/personas/$key/playbook-versions") } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
        } Extract { path<String>("id") }

        Given { this } When { post("/api/v1/personas/playbook-versions/$id/submit") } Then { statusCode(200) }
        Given { this } When { post("/api/v1/personas/playbook-versions/$id/publish") } Then {
            statusCode(409)
            body(containsString("maker cannot publish their own playbook version"))
        }
        Given { this } When { get("/api/v1/personas/$key/playbook/published") } Then { statusCode(404) }
        assertThat(playbookRow(UUID.fromString(id)).first).isEqualTo("IN_REVIEW")
    }

    @Test
    @TestSecurity(user = APPROVER, roles = ["ROLE_COMMS_APPROVER", "ROLE_API"])
    fun `a checker published playbook reaches the published surface and the answer search`() {
        val key = newPersona("e2e-playbook-publish")
        val id = UUID.randomUUID()
        seedPlaybook(id, personaId(key), maker = "playbook-editor@openbank.test")

        Given { this } When { post("/api/v1/personas/playbook-versions/$id/publish") } Then {
            statusCode(200)
            body("status", equalTo("PUBLISHED"))
        }
        Given { this } When { get("/api/v1/personas/$key/playbook/published") } Then {
            statusCode(200)
            body("personaKey", equalTo(key))
            body("approvedAnswers[0].answer", containsString("Kartu okamžitě zablokujeme"))
        }
        Given { queryParam("q", "klient ztratil kartu") } When {
            get("/api/v1/personas/$key/playbook/search")
        } Then {
            statusCode(200)
            body("[0].answer.situation", equalTo("klient ztratil kartu"))
        }
    }

    @Test
    @TestSecurity(user = APPROVER, roles = ["ROLE_COMMS_APPROVER", "ROLE_API"])
    fun `publishing a newer playbook retires the previous one under the one-published invariant`() {
        val key = newPersona("e2e-playbook-gen")
        val persona = personaId(key)
        val v1 = UUID.randomUUID()
        seedPlaybook(v1, persona, maker = "pb-gen-one@openbank.test", version = 1, status = "PUBLISHED")
        val v2 = UUID.randomUUID()
        seedPlaybook(v2, persona, maker = "pb-gen-two@openbank.test", version = 2, status = "IN_REVIEW")

        Given { this } When { post("/api/v1/personas/playbook-versions/$v2/publish") } Then {
            statusCode(200)
            body("status", equalTo("PUBLISHED"))
        }
        assertThat(playbookPublishedCount(persona)).isEqualTo(1)
        assertThat(playbookRow(v1).first).isEqualTo("RETIRED")
        // D4: the published surface now serves generation 2.
        Given { this } When { get("/api/v1/personas/$key/playbook/published") } Then {
            statusCode(200)
            body("playbookVersion", equalTo(2))
        }
    }

    @Test
    @TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR"])
    fun `golden set entry lifecycle and the approvals role gate`() {
        val key = newPersona("e2e-golden")
        val id = Given {
            contentType("application/json")
            body(
                """{"question":"Kolik mám na účtu?","expectedLanguage":"cs",
                    "expectNoFigureFromMemory":true,"expectedToneMarkers":["strucne"],
                    "requiredComplianceSentence":"Hovor je nahráván."}""",
            )
        } When { post("/api/v1/personas/$key/golden-set") } Then {
            statusCode(201)
            body("expectedLanguage", equalTo("cs"))
        } Extract { path<String>("id") }

        Given { this } When { get("/api/v1/personas/$key/golden-set") } Then {
            statusCode(200)
            body("", hasSize<Any>(1))
        }
        Given {
            contentType("application/json")
            body("""{"question":"  ","expectedLanguage":"cs"}""")
        } When { post("/api/v1/personas/$key/golden-set") } Then { statusCode(400) }
        // The approvals surface is approver-gated: an editor is refused before any decision logic.
        Given { this } When { get("/api/v1/communications/approvals") } Then { statusCode(403) }
        Given { this } When { delete("/api/v1/personas/golden-set/$id") } Then { statusCode(204) }
        Given { this } When { get("/api/v1/personas/$key/golden-set") } Then {
            statusCode(200)
            body("", hasSize<Any>(0))
        }
    }

    private fun draftStyle(personaKey: String): UUID = (
        Given {
            contentType("application/json")
            body(draftBody(basePublishedVersion = 0))
        } When { post("/api/v2/personas/$personaKey/style-versions") } Then { statusCode(201) }
        ).Extract { UUID.fromString(path<String>("id")) }
    private fun newPersona(prefix: String): String {
        val key = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                insert into persona (id, key, display_name, channel, language, description)
                values (?, ?, ?, 'written', 'cs', 'E2E persona')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, key)
                ps.setString(3, key)
                ps.executeUpdate()
            }
        }
        return key
    }

    private fun personaId(key: String): UUID = dataSource.connection.use { c ->
        c.prepareStatement("select id from persona where key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                rs.getObject(1, UUID::class.java)
            }
        }
    }

    @Suppress("LongParameterList") // one INSERT covering the row's not-null columns
    private fun seedStyle(
        id: UUID,
        personaId: UUID,
        version: Int,
        status: String,
        maker: String,
        basePublished: Int = 0,
        decidedBy: String? = null,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """insert into style_version
                    (id, persona_id, version, status, tone, formality, form_of_address, max_length,
                     preferred_terms, forbidden_terms, signature, maker, created_at,
                     decided_by, decided_at, published_at, retired_at, base_published_version)
                    values (?, ?, ?, ?, 'warm', 'informal', 'tykani', null, '{}', '[]', null, ?, ?, ?, ?, ?, null, ?)
                """.trimIndent(),
            ).use { ps ->
                val now = Timestamp.from(Instant.now())
                ps.setObject(1, id)
                ps.setObject(2, personaId)
                ps.setInt(3, version)
                ps.setString(4, status)
                ps.setString(5, maker)
                ps.setTimestamp(6, now)
                ps.setString(7, decidedBy)
                ps.setTimestamp(8, if (status != "IN_REVIEW") now else null)
                ps.setTimestamp(9, if (status == "PUBLISHED") now else null)
                ps.setInt(10, basePublished)
                ps.executeUpdate()
            }
        }
    }

    private fun seedPlaybook(
        id: UUID,
        personaId: UUID,
        maker: String,
        version: Int = 1,
        status: String = "IN_REVIEW",
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """insert into playbook_version
                    (id, persona_id, version, status, call_script, approved_answers, maker,
                     created_at, decided_by, decided_at, published_at, retired_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, null, null, ?, null)
                """.trimIndent(),
            ).use { ps ->
                val now = Timestamp.from(Instant.now())
                ps.setObject(1, id)
                ps.setObject(2, personaId)
                ps.setInt(3, version)
                ps.setString(4, status)
                ps.setString(5, CALL_SCRIPT_JSON)
                ps.setString(6, APPROVED_ANSWERS_JSON)
                ps.setString(7, maker)
                ps.setTimestamp(8, now)
                ps.setTimestamp(9, if (status == "PUBLISHED") now else null)
                ps.executeUpdate()
            }
        }
    }

    private fun playbookPublishedCount(personaId: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement(
            "select count(*) from playbook_version where persona_id = ? and status = 'PUBLISHED'",
        ).use { ps ->
            ps.setObject(1, personaId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun styleRow(id: UUID): Triple<String, String, String?> = dataSource.connection.use { c ->
        c.prepareStatement("select status, maker, decided_by from style_version where id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            }
        }
    }

    private fun playbookRow(id: UUID): Triple<String, String, String?> = dataSource.connection.use { c ->
        c.prepareStatement("select status, maker, decided_by from playbook_version where id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            }
        }
    }

    private fun styleCount(personaKey: String): Int = dataSource.connection.use { c ->
        c.prepareStatement(
            "select count(*) from style_version s join persona p on p.id = s.persona_id where p.key = ?",
        ).use { ps ->
            ps.setString(1, personaKey)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun publishedCount(personaId: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from style_version where persona_id = ? and status = 'PUBLISHED'")
            .use { ps ->
                ps.setObject(1, personaId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }

    private fun auditTrail(aggregateId: UUID): List<String> = dataSource.connection.use { c ->
        c.prepareStatement(
            "select type from communication_audit_event where aggregate_id = ? order by occurred_at, type",
        ).use { ps ->
            ps.setObject(1, aggregateId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
    }

    private fun draftBody(basePublishedVersion: Int, uiMessages: Boolean = false): String {
        val ui = if (uiMessages) """"uiMessages":{"cs.status.loading":"Hledám."},""" else ""
        return """
            {"tone":"warm","formality":"informal","formOfAddress":"tykani",
             $ui"basePublishedVersion":$basePublishedVersion}
        """.trimIndent()
    }

    companion object {
        private const val EDITOR = "editor@openbank.test"
        private const val APPROVER = "approver@openbank.test"

        private val PLAYBOOK_BODY = """
            {"callScript":[
                {"order":1,"kind":"GREETING","text":"Dobrý den, volám z OpenBank.","mandatory":true},
                {"order":2,"kind":"MANDATORY_SENTENCE","text":"Hovor je nahráván.","mandatory":true}
             ],
             "approvedAnswers":[
                {"situation":"klient ztratil kartu","answer":"Kartu okamžitě zablokujeme a vystavíme novou."}
             ]}
        """.trimIndent()

        private const val CALL_SCRIPT_JSON =
            """[{"order":1,"kind":"GREETING","text":"Dobrý den, volám z OpenBank.","mandatory":true}]"""

        private const val APPROVED_ANSWERS_JSON =
            """[{"situation":"klient ztratil kartu","answer":"Kartu okamžitě zablokujeme a vystavíme novou."}]"""
    }
}
