# Personal data breach register: template

GDPR Art. 33(5) requires the controller to document every personal data breach, notified or not.
Procedure: `docs/runbooks/0035-personal-data-breach-notification.md`.

**This file is a template, not the register.** This repository is public. A real entry would
publish the facts of a breach, the affected systems and the remediation state, possibly before
the weakness is fixed or the authority and data subjects are told. Real entries are kept in an
access-controlled register outside this repository, held by whoever acts as controller for the
deployment (for this reference platform: the maintainer). Never commit an entry here, not even
an anonymised one.

Columns every entry needs (Art. 33(3) and 33(5)):

| ID | First signal (UTC) | Awareness (UTC) | Kind (C / I / A) | Facts and affected systems | Data categories | Approx. data subjects / records | Effects | Risk decision and reasons | Authority notified (UTC, or reason not) | Reasons for any delay beyond 72 h (Art. 33(1)) | Data subjects informed (or Art. 34(3) exception) | Remedial action | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
