# Live governance rules reading

`GovernanceRulesReadAdapter` (ADR-0164) resolves the fleet's governance
`rules.yaml` from a live source instead of compiled-in mirror values, in this
order:

1. **Mounted file** — `openbank.rules-yaml-path` (`$RULES_YAML_PATH`) wins when
   present. The property is deliberately absent from `application.yaml`: an
   empty value would fail the config mapping at boot (`SRCFG00040`), so absence
   is the "disabled" state.
2. **Live URL** — `openbank.rules-yaml-url` (`$RULES_YAML_URL`, defaults to the
   raw GitHub URL of `openbank-libs/governance/rules.yaml` on `main`).
3. **Mirror fallback** — the `default-approvals` / `money-path-approvals`
   config values are used only when neither live source is reachable, and the
   divergence is logged at WARN so a stale mirror is never silent.

The parser is `RulesYamlSections` from `openbank-libs-runtime`, so the YAML is
read as data (never regexed) and the two governance controllers cannot drift
on how they interpret the same file. `RulesYamlSectionsTest` pins the parser
against the real `rules.yaml` in this repository.

An **empty** live `money_path_services` list is treated as "unavailable", not
as an answer: `RulesYamlSections` returns an empty set for an absent or renamed
section, and a 200 response that is not `rules.yaml` parses to exactly that.
Letting it win would silently downgrade every money-path PR to the default
approval obligation, so the mirror is used instead (and the divergence logged).
The fleet always has money-path services; an empty list is never a real state.
