# Runbook 0035: Personal data breach (GDPR Art. 33 / 34)

> Operational procedure for a **personal data breach** under Regulation (EU) 2016/679 (GDPR).
> It is the repository's interim procedure: there is no breach-detection or notification workflow
> in code yet (obligation `uoou-personal-data-breach` is still `missing` in
> `openbank-libs/governance/regulatory-obligations.yaml`; tracking: #15756).
> Quoted articles are summarised from the regulation; **verify against the current text** before
> relying on a detail, and take the national channel and form from the supervisory authority.
>
> **Not provided by this repository (organisational, to be set by the operating institution):**
> appointing the data protection officer (where one is required, Art. 37), the authority contact
> channel and account, the approved notification form, and the on-call rota. Until those exist,
> the first action of any breach is to find out who holds them. Role names only are used below.

## Trigger: what counts as a personal data breach here

Art. 4(12): a breach of security leading to the accidental or unlawful destruction, loss,
alteration, unauthorised disclosure of, or access to, personal data. Three kinds, any one is enough:

| Kind | Platform examples |
|---|---|
| **Confidentiality** (disclosure / access) | A wrong-party read through customer-edge (one customer or delegate sees another party's data); a leaked or publicly readable backup or export object; personal data in application logs or traces shipped to the log/trace store and readable beyond its intended audience; an agent or LLM prompt carrying customer data to the hosted LLM provider beyond the masked data classes; a mis-addressed statement, notification or e-mail; credentials of an operator account used by someone else. |
| **Integrity** (alteration) | Unauthorised or erroneous change of party, account or consent data; a bad migration or sync overwriting personal data. |
| **Availability** (loss / destruction) | Personal data lost with no usable backup; an erasure that did not propagate is the inverse case (data kept that must have gone) and is assessed under confidentiality/lawfulness, but still recorded here; a ransomware-style lockout of data subjects' data. |

A breach is not the same as a security incident: a P1 outage with no personal data affected is
not a personal data breach, and a P3 finding can be one. Declare it whenever personal data is
plausibly involved; classification can be revised, the clock cannot be restarted.

## The clock

| Duty | Rule | Deadline |
|---|---|---|
| Controller notifies the supervisory authority | Art. 33(1), unless the breach is **unlikely to result in a risk** to the rights and freedoms of natural persons | Without undue delay and, where feasible, **within 72 hours of becoming aware** |
| Late notification | Art. 33(1) | Must be accompanied by the **reasons for the delay** |
| Phased information | Art. 33(4) | Where all information is not available at once, it may be provided in phases without undue further delay |
| Processor to controller | Art. 33(2) | **Without undue delay** after becoming aware (no fixed number of hours). Applies where OpenBank services process data for an operating institution (controller) or where a sub-processor reports to us |
| Controller informs data subjects | Art. 34(1) | Without undue delay, where the breach is **likely to result in a high risk**; plain language (Art. 34(2)) |
| Exceptions to informing data subjects | Art. 34(3) | (a) data was unintelligible to anyone not authorised (e.g. effective encryption); (b) subsequent measures ensure the high risk is no longer likely to materialise; (c) it would involve disproportionate effort, in which case a public communication or similar equally effective measure is used. The authority may also require notification (Art. 34(4)) |
| Document every breach | Art. 33(5) | See [Breach register](#breach-register-art-335) |

**When the clock starts:** at *awareness*, i.e. when the controller has a reasonable degree of
certainty that a security incident has led to personal data being compromised (see the
European Data Protection Board guidelines on breach notification; verify against the current
version). Record two timestamps: first signal and awareness. Awareness is not the date the
register entry was created, and the processor's awareness is not automatically the controller's:
when we are the processor, the controller's own 72 hours run from its awareness, so Art. 33(2)
notification must be immediate.

## Roles

- **Incident commander**: declares the incident (see `docs/bcp/incident-response.md`), keeps the
  timeline and the clock, owns the decision record.
- **Data protection officer**: advises on risk and on the notification decision, is the contact
  point named in the notification (Art. 33(3)(b)). Appointment is organisational (see top note).
- **Service owner**: the owner of each affected service; supplies facts and containment.
- **Controller representative**: signs off the notification to the authority and to data subjects.

## Procedure

1. **T=0 first signal.** Record the timestamp and source. Declare an incident per
   `docs/bcp/incident-response.md` and answer its "Personal data involved?" question in the
   incident record.
2. **Contain** (stop the bleeding before measuring it): revoke the leaked credential or object
   access, disable the faulty route or flag, stop the prompt path to the LLM provider, rotate keys
   per runbook 0012 where key material is involved. Preserve evidence first where containment
   would destroy it.
3. **Collect evidence** (next section) and establish: what data, whose, how many, since when,
   who had access.
4. **Awareness decision** within 24 h of first signal at the latest: record the awareness
   timestamp, or the reason awareness is not yet reached and when it will be re-assessed.
5. **Assess risk** with the table below. Write the decision and its reasons, including "no risk".
6. **Notify** the authority (within 72 h of awareness) and, where high risk, the data subjects;
   use the template below. Send a first notification with what is known; complete in phases.
7. **Record** in the breach register, whatever the decision.
8. **Close**: post-incident review, corrective measures, and where a control gap is found, an issue
   per ADR-0052. Update the register entry with the final facts.

## Risk assessment

Factors (Art. 33(1), Art. 34(1), recitals 75 and 85; verify against the current text):

- **Data categories**: national identifiers (birth number, document numbers), financial data
  (accounts, balances, transactions, card data), authentication data, special categories (Art. 9,
  and criminal-offence data Art. 10), and data of vulnerable persons or minors.
- **Volume**: number of data subjects and of records.
- **Identifiability**: how easily a recipient can link the data to a person.
- **Encryption state**: whether the data was unintelligible and the key was not compromised.
- **Reversibility**: whether the data can be recalled, deleted by the recipient, or the credential
  revoked, and whether consequences (fraud, identity theft, discrimination, financial loss,
  reputational damage, loss of confidentiality of professional secrecy) are reversible.
- **Recipient**: known trusted party, unknown party, or the public.
- **Duration** of exposure and evidence of actual access or misuse.

| Assessment | Authority (Art. 33) | Data subjects (Art. 34) | Register (Art. 33(5)) |
|---|---|---|---|
| **No risk** (unlikely to result in a risk) | No | No | Yes, with the reasoning |
| **Risk** | Yes, within 72 h of awareness | No, unless it rises to high risk | Yes |
| **High risk** | Yes, within 72 h of awareness | Yes, without undue delay, unless an Art. 34(3) exception applies (record which) | Yes |

When in doubt between two rows, take the more protective one and record the doubt.

## Evidence to collect from this platform

| Source | What it answers | Retention (configured value) |
|---|---|---|
| **audit-service trail** (hash-chained audit events, ADR-0086 / ADR-0133) | Who did what to which party, when | Audit log kept 5 years (ADR-0118); not deleted by erasure |
| **Customer-visible access log**, `GET /privacy/access-log` (customer-edge) | Which actors read the party's data, as the data subject sees it; use it to confirm a wrong-party read and its extent | Session/access-log TTL 90 days in ADR-0118; the audit-service retention scheduler ships disabled by default, so check the actual environment setting |
| **Log store (Loki)** | Request and error lines around the event; also where to look for personal data in logs | `retention_period: 2160h` (90 days), `openbank-infra/gitops/apps/loki.yaml`; the bucket lifecycle must agree and the comment there records it was raised by hand, so verify the real lifecycle rule |
| **Trace store (Tempo)** | Request paths and attributes | `retention: 720h` (30 days), `openbank-infra/gitops/apps/tempo.yaml`; same lifecycle caveat |
| **Metrics (Prometheus)** | Volumes, error rates, timing of the event | `retention: 30d` with `retentionSize: 150GB`, `openbank-infra/gitops/apps/kube-prometheus-stack.yaml`; size limit may evict earlier |
| **Database backups and point-in-time recovery** | State of the data before the event; scope of an integrity breach | CNPG `retentionPolicy: "30d"` on the clusters (for example `openbank-infra/gitops/components/finrep/postgres.yaml`); confirm per cluster |
| **Keycloak events** (login, admin events) | Credential misuse, privilege changes | `eventsExpiration: 2592000` (30 days) in `openbank-infra/gitops/components/keycloak/realm-template.json` and `customers-realm-template.json` |
| **LLM tracing store** | Which prompts carried what to the LLM provider | Governed by the retention job under `openbank-infra/gitops/components/ai-platform/`; verify the value |

Timing matters: the shortest sources (Keycloak events and traces, 30 days) can expire while a late
discovery is still being analysed. Export what is relevant at declaration time and store it with
the incident record. Do not copy personal data into tickets, chat or this repository; reference
event identifiers instead.

## Notification content (Art. 33(3)) — fill-in template

```
To: <supervisory authority, via the channel and form it prescribes>
From: <controller representative role>          Date/time sent: <UTC>
Reference: <incident ID / register ID>

Awareness: first signal <UTC>; awareness <UTC>; notification is <within 72 h | late, reasons: ...>
Phase: <initial | update n | final>

(a) Nature of the breach: <confidentiality | integrity | availability>; what happened, how
    detected, period of exposure, systems/services involved.
    Categories and approximate number of data subjects: <categories, number or range>.
    Categories and approximate number of personal data records: <categories, number or range>.
(b) Contact point: <data protection officer or other contact role, channel>
(c) Likely consequences: <identity theft, fraud, financial loss, loss of confidentiality, ...>
(d) Measures taken or proposed: <containment, remediation, mitigation of adverse effects>
Data subjects informed: <yes, how and when | no, reason (Art. 34(3) exception or not high risk)>
Cross-border processing: <yes/no; other authorities concerned>
```

The notification to data subjects (Art. 34(2)) uses plain language and contains at least the
points (b), (c) and (d) above.

## Breach register (Art. 33(5))

Every breach is recorded, notified or not, with the facts, effects and remedial action, so the
authority can verify compliance. The register itself is kept outside this public repository, in
an access-controlled store held by the controller of the deployment: a public entry would disclose
a breach before it is fixed or notified. `docs/compliance/personal-data-breach-register.md` is only
the template of the columns an entry needs. Never commit an entry to this repository.

## Other clocks that may run for the same event

| | GDPR (this runbook) | DORA major ICT incident | CRA Art. 14 |
|---|---|---|---|
| Legal basis | Regulation (EU) 2016/679 Art. 33 | Regulation (EU) 2022/2554 Art. 19 and the RTS/ITS on classification and reporting | Regulation (EU) 2024/2847 Art. 14 |
| Reported to | Data protection supervisory authority | Financial supervisor named by the member state | ENISA single reporting platform / coordinating CSIRT |
| Starts at | Awareness of a personal data breach | Classification of the incident as major | Awareness of an actively exploited vulnerability or severe incident |
| Clocks | 72 h; data subjects without undue delay if high risk | Initial 4 h after classification and no later than 24 h after awareness; intermediate 72 h; final 1 month (verify against the current RTS) | Early warning 24 h; notification 72 h; final report 14 days or 1 month |
| Implemented in code | No (this runbook) | Classification not yet implemented, see #15752 | No separate fields, see runbook 0017 |

One event can start several clocks with different start times. Use one incident ID, keep a
separate decision and timestamps per regime, and never let one regime's report stand in for the
other. See `0017-cra-article-14-reporting.md` and `0018-cra-art14-tabletop-exercise.md` for the
CRA procedure and its exercise.

## Exercise

Run a tabletop for this runbook at least annually, scenario: a wrong-party read in customer-edge
found by a customer using the access log. Record measured times from first signal to awareness
decision and to a draft notification.
