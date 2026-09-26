-- call_script and approved_answers are Jackson-serialized JSON TEXT, same reasoning as
-- style_version.preferred_terms/forbidden_terms (V1): this service never queries into them from
-- SQL, only round-trips them through the application layer.
create table playbook_version (id uuid primary key, persona_id uuid not null references persona(id), version integer not null, status varchar(16) not null, call_script text not null default '[]', approved_answers text not null default '[]', maker varchar(255) not null, created_at timestamptz not null, decided_by varchar(255), decided_at timestamptz, published_at timestamptz, retired_at timestamptz, unique(persona_id, version));
create index playbook_version_persona_status_idx on playbook_version(persona_id, status);
create unique index playbook_version_one_published_per_persona on playbook_version(persona_id) where status = 'PUBLISHED';

-- Rollback: drop playbook_version.
