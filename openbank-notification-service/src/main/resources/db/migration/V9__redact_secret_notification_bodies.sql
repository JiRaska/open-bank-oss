-- Retire rendered authentication secrets already sitting in notifications.body.
--
-- OTP_CODE and PASSWORD_RESET render the code/token straight into the body, which is
-- readable by any ROLE_OPERATOR (NotificationResource + rest.rego `operator-read-any`).
-- A stored OTP defeats SCA (ADR-0021) and outlives its purpose (GDPR Art. 5(1)(c)).
-- NotificationConsumer no longer persists these rendered bodies; this clears the rows
-- written before that change. Delivery is unaffected — the body is rendered in-flight.
--
-- Data-loss note: intentional and one-way. The redacted bodies are spent secrets with no
-- business, audit or legal value; every other column (template, status, timestamps) is
-- untouched, so the delivery record itself survives intact.
--
-- Rollback: none possible, and none wanted — the pre-image is the secret this removes.
-- Reverting the code change alone is enough to restore the old (unsafe) write behaviour.
UPDATE notifications
SET body = '[REDACTED] Secret-bearing template: the rendered body is delivered to the customer '
        || 'but never stored (GDPR Art. 5(1)(c); a stored OTP would defeat SCA, ADR-0021).'
WHERE template IN ('OTP_CODE', 'PASSWORD_RESET');
