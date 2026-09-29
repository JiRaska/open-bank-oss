-- SPDX-License-Identifier: Apache-2.0
-- ADR-0310 D1 — account-opened facts, and the one-reward-per-referee-per-programme guard.
--
-- A referee opens the account during onboarding, BEFORE they can sign in and redeem an invite, so
-- the fact is recorded first and qualification happens at whichever moment comes second: the event
-- (invite already attributed) or the attribution (fact already recorded).
--
-- Rollback (reverse order, nothing outside this service depends on these objects):
--   DROP INDEX referral_invite_attributed_referee_idx;
--   DROP INDEX uq_referral_reward_referee_program;
--   DROP TABLE referral_qualifying_fact;

create table referral_qualifying_fact (
    id          uuid         primary key,
    party_id    uuid         not null,
    event_name  varchar(128) not null,
    -- The producer's envelope eventId. Unique, so a Kafka redelivery inserts nothing.
    event_id    varchar(255) not null,
    -- The account id for account.opened. Evidence only; never part of a decision.
    source_ref  varchar(255),
    occurred_at timestamptz  not null,
    recorded_at timestamptz  not null,
    constraint uq_referral_fact_event unique (event_id),
    -- The FIRST account a party opens is the fact; a second account is not a second qualification.
    constraint uq_referral_fact_party_event unique (party_id, event_name)
);

-- A referee who redeems several codes is rewarded once per programme. The service checks first and
-- records a QUALIFICATION_REJECTED audit row; this index is what holds when two paths race.
create unique index uq_referral_reward_referee_program on referral_reward (referee_party_id, program_id);

-- Event-time qualification looks invites up by referee.
create index referral_invite_attributed_referee_idx on referral_invite (referee_party_id)
    where status = 'ATTRIBUTED';
