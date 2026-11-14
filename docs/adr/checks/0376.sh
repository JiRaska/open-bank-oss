# DERIVED from docs/adr/0376-ongoing-screening-by-delta-a-monitored-subject-registry-in-sanctions-service.md `## Delivery check` by
# .github/scripts/gen-adr-delivery-checks.py — never hand-edit; edit the ADR and regenerate.
# Each command line's expectation is its trailing `# ...` comment, or the comment line above it.
grep -c 'sanctions_monitored_subjects' openbank-sanctions-service/src/main/resources/db/migration/V23__ongoing_monitoring.sql
grep -c 'monitored-subjects' openbank-sanctions-service/src/main/resources/openapi.yaml
grep -c 'MONITORED_SUBJECT_MATCHED' openbank-contracts/openbank-sanctions-service/asyncapi.yaml
git grep -l 'monitored-subjects' -- 'openbank-*/src/main/*' ':!openbank-sanctions-service'
git grep -l 'MONITORED_SUBJECT_MATCHED' -- 'openbank-*/src/main/*' ':!openbank-sanctions-service'
git grep -l 'monitored-subjects' -- 'openbank-*/src/main/*'
