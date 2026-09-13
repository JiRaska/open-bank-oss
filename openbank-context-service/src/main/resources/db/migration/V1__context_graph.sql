-- Additive P0/P1 schema. Rollback before production adoption: drop tables in reverse order.
-- After projections or audit records exist, retain the schema and disable consumers instead.
CREATE TABLE context_nodes (
  node_row_id uuid PRIMARY KEY,
  node_key varchar(300) NOT NULL,
  bank_scope varchar(80) NOT NULL,
  projection_generation bigint NOT NULL DEFAULT 1,
  namespace varchar(40) NOT NULL,
  node_type varchar(80) NOT NULL,
  source_system varchar(100) NOT NULL,
  source_ref varchar(200) NOT NULL,
  display_label varchar(200) NOT NULL,
  classification varchar(30) NOT NULL,
  valid_from timestamptz NOT NULL,
  valid_to timestamptz,
  recorded_at timestamptz NOT NULL,
  source_version bigint NOT NULL,
  CONSTRAINT context_nodes_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE (bank_scope, projection_generation, node_key)
);
CREATE INDEX idx_context_nodes_namespace_type ON context_nodes(bank_scope, projection_generation, namespace, node_type);
CREATE UNIQUE INDEX idx_context_nodes_source ON context_nodes(bank_scope, projection_generation, source_system, source_ref, node_type);

CREATE TABLE context_edges (
  edge_id uuid PRIMARY KEY,
  bank_scope varchar(80) NOT NULL,
  projection_generation bigint NOT NULL DEFAULT 1,
  namespace varchar(40) NOT NULL,
  from_key varchar(300) NOT NULL,
  to_key varchar(300) NOT NULL,
  relation_type varchar(100) NOT NULL,
  source_system varchar(100) NOT NULL,
  evidence_ref varchar(200) NOT NULL,
  valid_from timestamptz NOT NULL,
  valid_to timestamptz,
  recorded_at timestamptz NOT NULL,
  source_version bigint NOT NULL,
  CONSTRAINT context_edges_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
  FOREIGN KEY (bank_scope, projection_generation, from_key)
    REFERENCES context_nodes(bank_scope, projection_generation, node_key),
  FOREIGN KEY (bank_scope, projection_generation, to_key)
    REFERENCES context_nodes(bank_scope, projection_generation, node_key)
);
CREATE INDEX idx_context_edges_from ON context_edges(bank_scope, projection_generation, namespace, from_key);
CREATE INDEX idx_context_edges_to ON context_edges(bank_scope, projection_generation, namespace, to_key);

CREATE TABLE context_case_assignments (
  assignment_id uuid PRIMARY KEY,
  bank_scope varchar(80) NOT NULL,
  principal_id varchar(200) NOT NULL,
  case_id varchar(200) NOT NULL,
  purpose varchar(80) NOT NULL,
  valid_from timestamptz NOT NULL,
  valid_to timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT context_assignment_validity CHECK (valid_to > valid_from),
  UNIQUE (bank_scope, principal_id, case_id, purpose, valid_from)
);
CREATE INDEX idx_context_assignment_lookup ON context_case_assignments(bank_scope, principal_id, case_id, purpose, valid_to);

CREATE TABLE context_read_audit (
  audit_id uuid PRIMARY KEY,
  bank_scope varchar(80) NOT NULL,
  principal_id varchar(200) NOT NULL,
  case_id varchar(200) NOT NULL,
  purpose varchar(80) NOT NULL,
  action varchar(100) NOT NULL,
  root_ref varchar(300) NOT NULL,
  decision varchar(30) NOT NULL,
  policy_version varchar(100),
  reason_code varchar(80) NOT NULL,
  occurred_at timestamptz NOT NULL
);
CREATE INDEX idx_context_read_audit_case_time ON context_read_audit(bank_scope, case_id, occurred_at DESC);
