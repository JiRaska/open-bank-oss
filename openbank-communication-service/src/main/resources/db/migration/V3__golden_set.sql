-- D4's golden set: question/expected-properties pairs per persona. expected_tone_markers is
-- Jackson-serialized JSON TEXT, same reasoning as playbook_version's call_script (V2): this
-- service never queries into it from SQL, only round-trips it through the application layer.
create table golden_set_entry (id uuid primary key, persona_id uuid not null references persona(id), question text not null, expected_language varchar(16) not null, expect_no_figure_from_memory boolean not null default false, expected_tone_markers text not null default '[]', required_compliance_sentence text, created_by varchar(255) not null, created_at timestamptz not null);
create index golden_set_entry_persona_idx on golden_set_entry(persona_id);

-- Rollback: drop golden_set_entry.
