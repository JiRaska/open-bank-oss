-- Durable maker-checker lifecycle for investigation assignments. Assignment revocation is
-- deliberately immediate: removing access is safer than delaying it behind another approval.
-- Rollback: disable assignment administration endpoints, then drop assignment_change_audit and
-- context_assignment_proposals. Existing active assignments remain readable and can be expired.
CREATE TABLE context_assignment_proposals (
  proposal_id uuid PRIMARY KEY,
  bank_scope varchar(80) NOT NULL,
  principal_id varchar(200) NOT NULL,
  case_id varchar(200) NOT NULL,
  purpose varchar(80) NOT NULL,
  valid_from timestamptz NOT NULL,
  valid_to timestamptz NOT NULL,
  status varchar(20) NOT NULL,
  maker_id varchar(200) NOT NULL,
  checker_id varchar(200),
  assignment_id uuid REFERENCES context_case_assignments(assignment_id),
  created_at timestamptz NOT NULL,
  decided_at timestamptz,
  CONSTRAINT context_assignment_proposal_validity CHECK (valid_to > valid_from),
  CONSTRAINT context_assignment_proposal_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE INDEX idx_context_assignment_proposals_queue
  ON context_assignment_proposals(bank_scope, status, created_at);

CREATE TABLE context_assignment_change_audit (
  audit_id uuid PRIMARY KEY,
  bank_scope varchar(80) NOT NULL,
  assignment_id uuid,
  proposal_id uuid,
  action varchar(30) NOT NULL,
  actor_id varchar(200) NOT NULL,
  subject_id varchar(200) NOT NULL,
  case_id varchar(200) NOT NULL,
  purpose varchar(80) NOT NULL,
  occurred_at timestamptz NOT NULL
);
CREATE INDEX idx_context_assignment_change_audit_case
  ON context_assignment_change_audit(bank_scope, case_id, occurred_at DESC);
