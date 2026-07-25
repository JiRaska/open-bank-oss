-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Screen-feedback rendering context (ADR-0192 completion).
--
-- V3 shipped the feedback marts with platform + app_version only. The ADR's payload also names
-- the OS version, locale, theme and the pseudonymous session id, and the app and edge now send
-- them. This migration projects those four onto the gold layer.
--
-- CREATE OR REPLACE, not a V3 edit: V3's statements are all `CREATE VIEW IF NOT EXISTS`, so
-- editing that file changes nothing on a database where the views already exist — it would look
-- applied in git and silently do nothing in ClickHouse.
--
-- Backfill note: rows written before this ships have no such keys in `payload`, and
-- JSONExtractString returns '' for a missing key. Historic rows therefore read as empty strings,
-- not NULL — the same convention V3 already uses for an absent screenshot_key.
--
-- GDPR unchanged: session_id is client-generated and pseudonymous (never an account or device
-- identifier), and party_id remains the key an erasure request uses to find rows and objects.

CREATE OR REPLACE VIEW openbank_analytics.gold_screen_feedback AS
SELECT
    aggregate_id                                        AS reference,
    occurred_at,
    toDate(occurred_at)                                 AS day,
    JSONExtractString(payload, 'partyId')               AS party_id,
    JSONExtractString(payload, 'screenId')              AS screen_id,
    JSONExtractString(payload, 'category')              AS category,
    JSONExtractString(payload, 'comment')               AS comment,
    JSONExtractString(payload, 'platform')              AS platform,
    JSONExtractString(payload, 'appVersion')            AS app_version,
    JSONExtractString(payload, 'osVersion')             AS os_version,
    JSONExtractString(payload, 'locale')                AS locale,
    JSONExtractString(payload, 'theme')                 AS theme,
    JSONExtractString(payload, 'sessionId')             AS session_id,
    JSONExtractString(payload, 'screenshotKey')         AS screenshot_key,
    JSONExtractString(payload, 'screenshotStatus')      AS screenshot_status,
    JSONExtractUInt(payload, 'screenshotBytes')         AS screenshot_bytes
FROM openbank_analytics.bronze_events
WHERE aggregate_type = 'SCREEN_FEEDBACK';

-- WHERE IT BREAKS: the same screen can be fine on one OS/theme and broken on another, which is
-- exactly what platform+app_version alone could not show. Ranks the combinations that generate
-- bug reports, so a rendering fault gets attributed instead of being averaged away.
CREATE OR REPLACE VIEW openbank_analytics.gold_screen_feedback_context AS
SELECT
    screen_id,
    platform,
    os_version,
    theme,
    locale,
    countIf(category = 'BUG')   AS bugs,
    count()                     AS submissions,
    max(occurred_at)            AS last_seen
FROM openbank_analytics.gold_screen_feedback
GROUP BY screen_id, platform, os_version, theme, locale;
