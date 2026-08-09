-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- A campaign gains an optional recurring-enrolment schedule: a cadence key from ScheduleCatalog
-- (DAILY_MORNING, WEEKLY_MONDAY_MORNING, ...) plus the instant the schedule stops firing.
--
-- The cadence is a catalogue key, not a cron expression, for the same reason conversion_rule is a
-- key and not a query (V5): the expression and its time zone live in domain code, so a cadence can
-- be corrected by code review instead of a data migration, and a malformed cron — a schedule that
-- silently never fires — cannot be stored in the first place.
--
-- The firing itself is owned by Temporal, not by this table: these two columns are the campaign's
-- DECLARATION of its cadence, and TemporalCampaignScheduler reconciles the Temporal schedule from
-- them on every lifecycle transition. Storing a "next run" here would be a second source of truth
-- that drifts the moment Temporal skips or catches up a run.
--
-- Both nullable: every existing campaign is one-shot and reads back exactly as before. A null
-- schedule_cadence means enrolment happens only on POST /{id}/enrol, which is how campaigns have
-- worked until now. schedule_end_at is independently nullable — a cadence with no end runs until
-- the campaign is paused or closed.
--
-- Rollback:
--   ALTER TABLE campaigns DROP COLUMN schedule_cadence;
--   ALTER TABLE campaigns DROP COLUMN schedule_end_at;
-- Note that dropping these leaves any Temporal schedules in place; delete them with
--   tctl schedule delete --sid campaign-enrolment-<campaignId>
-- or the campaigns keep enrolling on a cadence the database no longer records.
ALTER TABLE campaigns ADD COLUMN schedule_cadence varchar(64);
ALTER TABLE campaigns ADD COLUMN schedule_end_at timestamptz;
