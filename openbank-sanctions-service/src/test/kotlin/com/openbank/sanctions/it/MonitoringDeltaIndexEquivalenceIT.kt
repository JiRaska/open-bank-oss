// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.infrastructure.persistence.repository.MonitoredSubjectRepositoryImpl
import com.openbank.sanctions.infrastructure.persistence.repository.MonitoringNameIndex
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import com.openbank.sanctions.it.MonitoringFixture.Companion.SUBJECT_PREFIX
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * The index-driven delta match returns exactly what one pass over the whole registry returns
 * (ADR-0376): the same subjects with the same scores, for every entry text and threshold tried.
 *
 * The registry is synthetic and built to be awkward: names of one to seven words, two-letter
 * names, names that normalise to nothing searchable, punctuation, digits, non-Latin scripts
 * (whose trigrams pg_trgm hashes), shared given names and family names, and aliases. Entry texts
 * are built FROM registry names by the edits a list would carry (a typo, a dropped or added
 * letter, a missing or extra word, swapped word order) and joined several to a text, so many
 * pairs score close to the threshold on either side. Every name here is invented.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class MonitoringDeltaIndexEquivalenceIT {

    @Inject
    lateinit var registry: MonitoredSubjectRepositoryImpl

    @Inject
    lateinit var entries: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var db: MonitoringFixture
    private lateinit var names: List<String>

    @BeforeEach
    fun setUp() {
        db = MonitoringFixture(dataSource, entries)
        db.cleanUp()
        names = plantRegistry()
    }

    @AfterEach
    fun tearDown() = db.cleanUp()

    @Test
    fun `the index-driven match equals the full scan for every entry and threshold`() {
        val random = Random(SEED + 1)
        val texts = (1..ENTRIES).map { entryText(random) } + EDGE_TEXTS
        var matches = 0
        var nearThreshold = 0
        var textsWithMatches = 0
        for (threshold in listOf(CUSTOMER_FLOOR, 0.85, 0.4)) {
            for (text in texts) {
                val byIndex = db.onEventLoop { registry.findMatching(text, threshold, NO_LIMIT) }
                val byScan = db.onEventLoop { registry.findMatchingByFullScan(text, threshold, NO_LIMIT) }
                assertThat(byIndex.associate { it.subject.id to it.score })
                    .describedAs("threshold %s, entry text '%s'", threshold, text)
                    .isEqualTo(byScan.associate { it.subject.id to it.score })
                assertThat(byIndex).describedAs("no subject twice").hasSameSizeAs(byScan)
                matches += byScan.size
                if (byScan.isNotEmpty()) textsWithMatches++
                nearThreshold += byScan.count { it.score < threshold + NEAR }
            }
        }
        // The comparison is only worth something if both sides had plenty to disagree about.
        assertThat(matches).describedAs("matches compared").isGreaterThan(2_000)
        assertThat(textsWithMatches).describedAs("entry texts with a match").isGreaterThan(200)
        assertThat(nearThreshold).describedAs("matches just above their threshold").isGreaterThan(200)
    }

    @Test
    fun `the stored size class of every name is the one the probe arithmetic assumes`() {
        val stored = db.query(
            "SELECT DISTINCT trigram_count, size_class, cardinality(show_trgm(search_name)) " +
                "FROM sanctions_monitored_subject_names",
        ) { Triple(it.getInt(1), it.getInt(2), it.getInt(3)) }

        assertThat(stored.map { it.second }.distinct().size).describedAs("size classes in use").isGreaterThan(6)
        for ((trigrams, sizeClass, recomputed) in stored) {
            assertThat(trigrams).isEqualTo(recomputed)
            if (trigrams == 0) {
                assertThat(sizeClass).describedAs("a name without trigrams is in no indexed class").isZero()
            } else {
                assertThat(trigrams).describedAs("class %s", sizeClass)
                    .isBetween(MonitoringNameIndex.smallest(sizeClass), MonitoringNameIndex.largest(sizeClass))
            }
        }
    }

    @Test
    fun `each probe is served by the partial trigram index of its size class`() {
        for (sizeClass in MonitoringNameIndex.CLASSES) {
            assertThat(probePlan(sizeClass)).contains("idx_sanctions_monitored_name_trgm_c$sizeClass")
        }
    }

    private fun probePlan(sizeClass: Int): String = dataSource.connection.use { c ->
        c.autoCommit = false
        // Not a tuning knob of the service: it only makes the planner show whether the index CAN
        // serve the predicate, on a table small enough to be scanned instead. Local to this
        // transaction, which the rollback ends.
        c.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
        val plan = c.createStatement().use { s ->
            s.executeQuery(
                "EXPLAIN SELECT n.subject_id FROM sanctions_monitored_subject_names n " +
                    "WHERE n.size_class = $sizeClass AND n.search_name % 'zorvath quellinbrook'",
            ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.joinToString("\n") }
        }
        c.rollback()
        plan
    }

    @Test
    fun `the limit bounds the answer of the index-driven match`() {
        val common = names.first { it.split(' ').size == 2 }
        val all = db.onEventLoop { registry.findMatchingByFullScan(common, 0.4, NO_LIMIT) }
        assertThat(all.size).isGreaterThan(3)

        assertThat(db.onEventLoop { registry.findMatching(common, 0.4, 3) }).hasSize(3)
        assertThat(db.onEventLoop { registry.findMatching(common, 0.4, 0) }).isEmpty()
    }

    /** @return the search names planted, one or two per subject. */
    private fun plantRegistry(): List<String> {
        val random = Random(SEED)
        val given = (1..GIVEN_POOL).map { word(random, 1 + random.nextInt(3)) }
        val family = (1..FAMILY_POOL).map { word(random, 2 + random.nextInt(3)) }
        val planted = mutableListOf<String>()
        dataSource.connection.use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "INSERT INTO sanctions_monitored_subjects " +
                    "(id, owner_service, subject_ref, entity_type, name, search_names, registered_at, updated_at) " +
                    "VALUES (?, 'openbank-kyc', ?, 'INDIVIDUAL', ?, ?, now(), now())",
            ).use { insert ->
                repeat(SUBJECTS) { i ->
                    val searchNames = buildList {
                        add(personName(random, given, family, i))
                        if (i % 4 == 0) add(personName(random, given, family, i + 1))
                    }.distinct()
                    planted += searchNames
                    insert.setObject(1, UUID.nameUUIDFromBytes("eq-$i".toByteArray()))
                    insert.setString(2, "${SUBJECT_PREFIX}eq-$i")
                    insert.setString(3, searchNames.first())
                    insert.setArray(4, c.createArrayOf("text", searchNames.toTypedArray()))
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            c.createStatement().use {
                it.execute(
                    "INSERT INTO sanctions_monitored_subject_names (subject_id, search_name) " +
                        "SELECT s.id, n FROM sanctions_monitored_subjects s CROSS JOIN unnest(s.search_names) AS n",
                )
            }
            c.commit()
        }
        return planted
    }

    private fun personName(random: Random, given: List<String>, family: List<String>, i: Int): String = when (i % 40) {
        0 -> SPECIAL_NAMES[(i / 40) % SPECIAL_NAMES.size]
        1 -> (1..7).joinToString(" ") { skewed(random, family) }
        2 -> skewed(random, given).take(2)
        3 -> skewed(random, given) + "-" + skewed(random, given) + " o'" + skewed(random, family)
        else -> buildString {
            append(skewed(random, given))
            if (i % 6 == 0) append(' ').append(skewed(random, given))
            append(' ').append(skewed(random, family))
        }
    }

    /** One to five registry names, edited, joined the way `search_text` joins an entry's names. */
    private fun entryText(random: Random): String = (1..(1 + random.nextInt(5))).joinToString(" | ") {
        val words = names[random.nextInt(names.size)].split(' ').toMutableList()
        val at = random.nextInt(words.size)
        val word = words[at]
        when (random.nextInt(9)) {
            0 -> Unit
            1 -> if (word.length > 2) words[at] = word.replaceRange(1, 2, "x")
            2 -> if (word.length > 3) words[at] = word.removeRange(2, 3)
            3 -> words[at] = word + "a"
            4 -> if (words.size > 1) words.removeAt(at)
            5 -> words.add(at, word(random, 2))
            6 -> words.reverse()
            7 -> if (word.length > 4) words[at] = word.take(word.length / 2)
            else -> words[at] = word(random, 2 + random.nextInt(2))
        }
        words.joinToString(" ")
    }

    private fun word(random: Random, syllables: Int): String =
        (1..syllables).joinToString("") { ONSETS.random(random) + VOWELS.random(random) + CODAS.random(random) }

    /** Skewed towards the head of the pool, as given names and family names are in a population. */
    private fun skewed(random: Random, pool: List<String>): String {
        val r = random.nextDouble()
        return pool[(pool.size * r * r * r).toInt()]
    }

    private companion object {
        const val SEED = 20_261_112
        const val SUBJECTS = 3_000
        const val ENTRIES = 110
        const val GIVEN_POOL = 120
        const val FAMILY_POOL = 900
        const val CUSTOMER_FLOOR = 0.65
        const val NEAR = 0.08
        const val NO_LIMIT = 1_000_000

        val ONSETS = "b d f g h k l m n p r s t v z br kl st tr".split(' ') + ""
        val VOWELS = listOf("a", "e", "i", "o", "u", "ia", "ou")
        val CODAS = listOf("", "", "n", "r", "s", "l", "k")

        /** Already in the normal form registration stores: lower case, no combining marks. */
        val SPECIAL_NAMES = listOf(
            "li",
            "x",
            "--- ---",
            "o'bri-en mc.kay",
            "agent 007 unit 42",
            "иванов сергей петрович",
            "μαρια παπαδοπουλου",
            "strasse weiß",
            "al al al al al",
            "a b c d e f g",
            "zorvath quellinbrook",
            "zorvath",
            "quellinbrook zorvath",
        )

        val EDGE_TEXTS = listOf(
            "",
            " | ",
            "---",
            "li",
            "x",
            "zorvath quellinbrook",
            "quellinbrook | zorvath",
            "иванов сергей",
            "μαρια",
            "weiß",
            "al",
            "agent 007",
            "o'bri-en",
            "a b c d e f g h i j k l m n o p",
        )
    }
}
