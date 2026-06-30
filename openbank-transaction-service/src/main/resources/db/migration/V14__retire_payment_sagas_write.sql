-- V14: Tombstone the payment_sagas write path (ADR-0120 Phase 5, 2026-06-30).
-- The PaymentSagaOrchestrator has been removed; Temporal is the sole orchestrator.
-- Historical rows are preserved for the audit window (rules.yaml api_deprecation.min_sunset_window_days).
-- No application code writes to this table after this migration.
--
-- Rollback: no schema change — revert by re-deploying a service version that includes
-- PaymentSagaOrchestrator (any version < 1.13.0).
COMMENT ON TABLE payment_sagas IS 'RETIRED (ADR-0120 Phase 5, 2026-06-30): write path replaced by Temporal workflow (openbank-payment-execution task queue). Historical rows preserved for audit window; table is read-only from application perspective.';
