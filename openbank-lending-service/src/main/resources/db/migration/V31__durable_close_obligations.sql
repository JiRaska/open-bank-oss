-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Observing a due job is not evidence that it ran. Retain that obligation across cron/config changes.
-- Rollback: revert reporting code; retain this table and all run evidence. Never infer historical schedules.
CREATE TABLE lending_close_required_job (
    business_date DATE NOT NULL,
    job VARCHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_date, job)
);
