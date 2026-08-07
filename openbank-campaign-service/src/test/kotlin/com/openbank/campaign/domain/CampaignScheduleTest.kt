// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.ScheduleCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class CampaignScheduleTest {

    @Test
    fun `a cadence outside the catalogue is rejected at construction`() {
        // The whole reason the cadence is a key: a cron typed into a request body can be malformed,
        // and a malformed schedule does not fail — it silently never fires.
        assertThatThrownBy { CampaignSchedule("EVERY_SECOND_TUESDAY") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown cadence")
            .hasMessageContaining("DAILY_MORNING")
    }

    @Test
    fun `every catalogued cadence is constructible`() {
        // Guards the pair: a catalogue entry the domain type rejects would be an option the console
        // offers and the service refuses.
        assertThat(ScheduleCatalog.ALL.keys).isNotEmpty()
        ScheduleCatalog.ALL.keys.forEach { assertThat(CampaignSchedule(it).cadence).isEqualTo(it) }
    }

    @Test
    fun `a schedule with no end never expires`() {
        assertThat(CampaignSchedule("DAILY_MORNING").expiredAt(Instant.parse("2099-01-01T00:00:00Z")))
            .describedAs("null endAt means run until paused or closed, not run until some default")
            .isFalse()
    }

    @Test
    fun `expiry is inclusive of the end instant`() {
        val end = Instant.parse("2026-09-01T00:00:00Z")
        val schedule = CampaignSchedule("DAILY_MORNING", end)

        assertThat(schedule.expiredAt(end.minusSeconds(1))).isFalse()
        // At exactly endAt the schedule is over. The boundary is asserted because "ends on 1 Sep"
        // firing once more at 09:00 on 1 Sep is the kind of off-by-one nobody notices until a
        // campaign contacts people after its approved window.
        assertThat(schedule.expiredAt(end)).isTrue()
        assertThat(schedule.expiredAt(end.plusSeconds(1))).isTrue()
    }
}

class ScheduleCatalogTest {

    @Test
    fun `every cadence declares a five-field cron and a human sentence`() {
        ScheduleCatalog.ALL.forEach { (key, cadence) ->
            assertThat(cadence.cron.trim().split(Regex("\\s+")))
                .describedAs("cadence %s must be a five-field cron Temporal can parse", key)
                .hasSize(5)
            assertThat(cadence.humanForm)
                .describedAs("cadence %s needs the sentence an operator approves", key)
                .isNotBlank()
        }
    }

    @Test
    fun `no cadence is finer than daily`() {
        // The catalogue's whole safety argument: every run fans out over a segment and starts one
        // workflow per newly-qualifying party. An hourly cadence is a load generator, and the
        // cheapest guard is not offering it. A new entry with `*` in the hour field fails here.
        ScheduleCatalog.ALL.forEach { (key, cadence) ->
            val (minute, hour) = cadence.cron.split(Regex("\\s+"))
            assertThat(minute).describedAs("cadence %s fires every minute", key).isNotEqualTo("*")
            assertThat(hour).describedAs("cadence %s fires every hour", key).isNotEqualTo("*")
        }
    }

    @Test
    fun `the zone is a real one, and is not UTC`() {
        // Stating the zone is the point: Temporal evaluates a cron in UTC unless told otherwise, so
        // a 09:00 campaign would reach Czech customers at 11:00 in summer. If this ever becomes UTC
        // the whole catalogue silently shifts by an hour twice a year.
        assertThat(java.time.ZoneId.getAvailableZoneIds()).contains(ScheduleCatalog.ZONE)
        assertThat(ScheduleCatalog.ZONE).isNotEqualTo("UTC")
    }
}
