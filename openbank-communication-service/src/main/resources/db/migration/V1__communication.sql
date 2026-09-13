-- preferred_terms/forbidden_terms are Jackson-serialized JSON TEXT, not jsonb: this service
-- never queries into them from SQL, only round-trips them through the application layer, so
-- the extra jsonb type-mapping machinery buys nothing here.
create table persona (id uuid primary key, key varchar(64) not null unique, display_name varchar(128) not null, channel varchar(32) not null, language varchar(8) not null, description text not null);
create table style_version (id uuid primary key, persona_id uuid not null references persona(id), version integer not null, status varchar(16) not null, tone varchar(64) not null, formality varchar(32) not null, form_of_address varchar(32) not null, max_length integer, preferred_terms text not null default '{}', forbidden_terms text not null default '[]', signature text, maker varchar(255) not null, created_at timestamptz not null, decided_by varchar(255), decided_at timestamptz, published_at timestamptz, retired_at timestamptz, unique(persona_id, version));
create index style_version_persona_status_idx on style_version(persona_id, status);
create unique index style_version_one_published_per_persona on style_version(persona_id) where status = 'PUBLISHED';
create table communication_audit_event (id uuid primary key, type varchar(64) not null, aggregate_id uuid not null, actor varchar(255) not null, details text not null, occurred_at timestamptz not null);
create index communication_audit_aggregate_idx on communication_audit_event(aggregate_id, occurred_at);

-- ADR-0285 D2: closed, deploy-time-declared persona catalogue for phase 2. rm-copilot and
-- ui-assistant join in phase 4 (delivery phases, ADR-0285) once they actually consume this
-- service; seeding them now would let an editor draft styles nothing ever reads.
insert into persona (id, key, display_name, channel, language, description) values
  (gen_random_uuid(), 'customer-copilot', 'Customer Copilot', 'mobile', 'cs', 'The mobile app assistant customers talk to directly.'),
  (gen_random_uuid(), 'contact-centre', 'Contact Centre', 'phone', 'cs', 'Human agents on the phone/chat channel; renders as an agent-assist read view.'),
  (gen_random_uuid(), 'back-office-written', 'Back Office (Written)', 'written', 'cs', 'Letters and e-mail replies from back-office staff.'),
  (gen_random_uuid(), 'collections', 'Collections', 'written', 'cs', 'Arrears and collections correspondence.');

-- Rollback: drop communication_audit_event, style_version, persona in reverse order.
