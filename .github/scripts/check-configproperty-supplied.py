#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A required @ConfigProperty must have a value somewhere, or the service dies at boot.

WHY THIS EXISTS
---------------
The root CLAUDE.md has documented this footgun for months with nothing enforcing it:

    `@ConfigProperty` optional fields must be `Optional<String>`, not plain `String`, or a
    missing value throws `SRCFG00040` at boot.

campaign-service shipped the same shape as #2872's second defect — `Failed to load config value …
analytics.clickhouse-user`, credentials never wired — and it was on `main`, merged, with every
required check green. A missing value for a NON-optional property is not a warning: SmallRye
refuses to start the application.

WHAT IT CHECKS
--------------
For every `@ConfigProperty(name = "…")` in a service's `src/main` that is
  - NOT declared `Optional<…>`, and
  - has NO `defaultValue`,
the property must be supplied by EITHER the service's own `application.yaml` (including a
`%profile` root) OR an env var on its gitops workload (Quarkus' `FOO_BAR` mapping).

Those three together are the whole contract: a property is required, so it needs a value; a
property with a default or an `Optional` type does not.

WHY BOTH SOURCES
----------------
Because either alone is a false positive waiting to happen. Several services here supply config
purely from their Deployment env and say nothing about it in `application.yaml`. A guard that models
only the source its author happened to think of reports the author's imagination back as a finding —
learned expensively on a different gate the same night (#3444/#3453).

WHAT IT ALSO CHECKS (#5946) — "DEFINED AS EMPTY"
------------------------------------------------
A second, opposite failure: the property IS supplied, and supplied as nothing.

`application.yaml` lines of the form `key: ${SOME_ENV:}` DEFINE the property as the empty string
when the env var is unset. SmallRye reads an empty value as no value, so injection into a
non-`Optional` target throws `SRCFG00040` and **Quarkus never starts** — and any `require()` below
it is dead code that can never run. That is #5844: `openbank.audit.anchor.kms-key-id` was a plain
`String` bound to `${AUDIT_ANCHOR_KMS_KEY_ID:}`, `main` was red for days, and path-scoped CI never
re-ran the module to say so.

The first check above could not see it, and the reason is worth stating because it is the general
trap: that check SKIPS any declaration carrying a `defaultValue`, and it counts a key present in
`application.yaml` as "supplied". #5844 had both. A guard whose two escape hatches are exactly the
two properties of the defect reports it as clean.

AND `defaultValue = ""` IS NOT A DEFAULT — MEASURED, NOT ASSUMED
----------------------------------------------------------------
Measured 2026-08-21 with a throwaway `@ConfigProperty(name = "…defined.nowhere", defaultValue = "")`
injected into a plain `String` inside a real `@QuarkusTest`. Quarkus did not start:

    DeploymentException: Failed to load config value of type class java.lang.String
      for: openbank.test.issue5946.defined.nowhere
    Suppressed: SRCFG00014: The config property … is required but it could not be found
      in any config source
      at io.quarkus.arc.runtime.ConfigRecorder.validateConfigProperties(ConfigRecorder.java:70)

Two things fall out of that stack trace, and both contradict what #5946 guessed at:

  * An EMPTY `defaultValue` is not a value. SmallRye reports the property as absent from every
    config source, so `defaultValue = ""` leaves the declaration exactly as required as one with
    no `defaultValue` at all. The original check skipped anything carrying the token
    `defaultValue`, which is why the shape sailed through.
  * `ConfigRecorder.validateConfigProperties` runs at STARTUP, over every injection point at once.
    `@ApplicationScoped` laziness does not defer it — the bean's scope is irrelevant, the service
    simply does not boot. #5946 hypothesised a latent first-use failure; it is a boot failure.

So an empty `defaultValue` is treated here as no default, and the "must be supplied somewhere"
rule above applies to it unchanged.

THE SECOND ARM: DEFINED, AS EMPTY
---------------------------------
    property is DEFINED in the service's application.yaml with a value that resolves empty
    AND the injection target is not Optional<…>          =>  SRCFG00040 at injection

That one is #5844 itself, and it is checked BEFORE the "is it supplied" question, because the
answer to that question is yes — supplied as nothing.

WHAT IT STILL DOES NOT CHECK
----------------------------
That a value is CORRECT, or that a non-empty `defaultValue` is a sensible one. And it cannot see a
value defined as empty by a source outside this repo (a ConfigMap literal ""), because there is
nothing here to read.

Usage:  check-configproperty-supplied.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import re
import sys

import yaml

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra/gitops/components"
WORKLOADS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet"}
SKIP_PREFIXES = ("openbank-libs",)

# Known-and-accepted defined-as-empty injections, if any ever need to be. Empty today (#5946
# closed the only one). An entry needs `path::property  # reason` and is reported when it stops
# matching, so a debt cannot quietly become permanent.
EMPTY_BASELINE_FILE = pathlib.Path(__file__).with_name("configproperty-defined-as-empty-baseline.txt")
EMPTY_BASELINE = {
    line.split("#", 1)[0].strip()
    for line in (EMPTY_BASELINE_FILE.read_text().splitlines() if EMPTY_BASELINE_FILE.exists() else [])
    if line.split("#", 1)[0].strip()
}

# `@ConfigProperty(name = "x.y")` followed by the declaration whose type decides whether it is
# required. Kotlin allows annotations and modifiers in between, hence the tolerant middle.
CONFIG_RE = re.compile(
    r'@ConfigProperty\s*\(\s*name\s*=\s*"([^"]+)"([^)]*)\)'
    r'\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    # `va[lr]` is OPTIONAL on purpose. A @Produces METHOD PARAMETER has neither — and that is
    # precisely the #5844 shape, so the pattern that required them was structurally unable to see
    # the defect this gate was extended to catch (#5946). Same class of blind spot as the
    # `defaultValue` skip below: the check could not express the failure it was named for.
    r'(?:private\s+|internal\s+|public\s+)?(?:lateinit\s+)?(?:va[lr]\s+)?(\w+)\s*:\s*([A-Za-z_][\w.]*)',
    re.S,
)


def env_key(prop: str) -> str:
    return prop.upper().replace(".", "_").replace("-", "_")


def deployment_env(components: pathlib.Path) -> dict[str, set[str]]:
    out: dict[str, set[str]] = collections.defaultdict(set)
    if not components.is_dir():
        return out
    for path in gatelib.rglob(components, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, OSError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOADS:
                continue
            name = (doc.get("metadata") or {}).get("name", "")
            podspec = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            for container in podspec.get("containers") or []:
                for entry in container.get("env") or []:
                    if entry.get("name"):
                        out[name].add(entry["name"])
    return out


def declared_keys(application_yaml: pathlib.Path) -> set[str]:
    """Dotted property names present in the file, parsed rather than grepped.

    Parsed on purpose: a comment naming a property must not count as supplying it. That collision
    runs silently in the direction that matters — a stale comment would mark a service that cannot
    boot as configured.
    """
    if not application_yaml.is_file():
        return set()
    try:
        doc = gatelib.load_yaml(application_yaml) or {}
    except (yaml.YAMLError, OSError, UnicodeDecodeError):
        return set()

    def walk(node, prefix=""):
        if not isinstance(node, dict):
            return
        for key, value in node.items():
            path = f"{prefix}{key}"
            yield path
            yield from walk(value, path + ".")

    keys = set(walk(doc))
    for key, value in doc.items():
        if isinstance(key, str) and key.startswith("%"):
            keys |= set(walk(value))
    return keys


# `${SOME_ENV:}` — an expansion whose fallback is empty. Unset env var => the property is DEFINED
# as the empty string, which is not the same as absent and is the whole point of #5946.
EMPTY_EXPANSION = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*:\s*\}$")

# Type names that carry absence explicitly. Written as a suffix match because both the imported
# short form and the fully-qualified `java.util.Optional` occur in this tree — matching only the
# short form silently reclassifies two correct beans (notification, product-catalog) as defects.
OPTIONAL_TYPES = ("Optional", "OptionalInt", "OptionalLong", "OptionalDouble")


def is_optional(type_name: str) -> bool:
    return type_name.rsplit(".", 1)[-1] in OPTIONAL_TYPES


def empty_valued_keys(application_yaml: pathlib.Path) -> dict[str, str]:
    """Dotted property names whose value in this file RESOLVES EMPTY.

    Three shapes, all equivalent to SmallRye: a `${VAR:}` expansion with an empty fallback, a
    literal empty/blank scalar, and a key with no value at all (YAML null).

    Parsed, not grepped, for the same reason as declared_keys: a commented-out line must not count.
    """
    if not application_yaml.is_file():
        return {}
    try:
        doc = gatelib.load_yaml(application_yaml) or {}
    except (yaml.YAMLError, OSError, UnicodeDecodeError):
        return {}

    def walk(node, prefix=""):
        if not isinstance(node, dict):
            return
        for key, value in node.items():
            path = f"{prefix}{key}"
            if value is None:
                yield path, ""
            elif isinstance(value, str) and (value.strip() == "" or EMPTY_EXPANSION.match(value.strip())):
                yield path, value
            yield from walk(value, path + ".")

    out = dict(walk(doc))
    # A `%profile` root defines the same property names one level down.
    for key, value in doc.items():
        if isinstance(key, str) and key.startswith("%"):
            out.update(dict(walk(value)))
    return out


def findings(repo: pathlib.Path = REPO) -> tuple[list[str], int, int]:
    env = deployment_env(repo / "openbank-infra/gitops/components")
    out: list[str] = []
    required = 0
    empty_shaped = 0
    empty_keys_seen = 0
    subjects = 0

    for main in gatelib.glob(repo, "openbank-*/src/main"):
        service = main.parts[len(repo.parts)]
        if service.startswith(SKIP_PREFIXES):
            continue
        keys = declared_keys(main / "resources/application.yaml")
        empty = empty_valued_keys(main / "resources/application.yaml")
        empty_keys_seen += len(empty)
        short = service.removeprefix("openbank-")
        supplied_env: set[str] = set()
        for candidate in (service, short, f"{short}-service"):
            supplied_env |= env.get(candidate, set())

        for source in gatelib.rglob(main, "*.kt"):
            try:
                text = gatelib.read_text(source, errors="ignore")
            except OSError:
                continue
            for match in CONFIG_RE.finditer(text):
                prop, params, type_name = match.group(1), match.group(2), match.group(4)
                # Every @ConfigProperty declaration this gate looked at, whatever the verdict.
                # `required` counts only the subset that must be supplied, so it is the wrong
                # number for a collapse floor: it would keep shrinking as the fleet correctly
                # adopts Optional and real defaults.
                subjects += 1
                rel = source.relative_to(repo)
                line = text[: match.start()].count("\n") + 1

                # #5946: a value DEFINED as empty outranks any annotation defaultValue, so this
                # arm is deliberately checked BEFORE the two skips below — those skips are exactly
                # what hid #5844 for days.
                if not is_optional(type_name) and prop in empty:
                    empty_shaped += 1
                    key = f"{rel}::{prop}"
                    if key not in EMPTY_BASELINE:
                        out.append(
                            f"{rel}:{line}: @ConfigProperty(\"{prop}\") is injected into a "
                            f"non-Optional {type_name}, and {service}'s application.yaml DEFINES it "
                            f"as empty (`{empty[prop]}`). SmallRye reads an empty value as no "
                            f"value, so injection throws SRCFG00040 and the service never starts — "
                            f"before any require()/check() in the body can run. This is #5844 "
                            f"exactly. Declare it Optional<{type_name}> and handle absence, or give "
                            f"the yaml expansion a real non-empty fallback.")
                    continue

                # An empty defaultValue is NOT a default (SRCFG00014, measured — see the module
                # docstring), so it must not buy an exemption here.
                # Only a LITERAL `defaultValue = ""` counts as absent. `defaultValue` given as a
                # named constant (`DEFAULT_CACHE_TTL_MS`, `OpaSidecarPolicyDecisionPoint.
                # DEFAULT_BASE_URL`) is a real default and must stay exempt — reading "no string
                # literal" as "no default" turned three correct beans into findings while this
                # arm was being written. A constant whose own value is "" is out of reach here
                # and is left to the boot-time failure it causes.
                empty_default = re.search(r'defaultValue\s*=\s*""(?!")', params) is not None
                if (("defaultValue" in params and not empty_default) or is_optional(type_name)):
                    continue
                required += 1
                if prop in keys or env_key(prop) in supplied_env:
                    continue
                out.append(
                    f"{rel}: @ConfigProperty(\"{prop}\") is required — not Optional, no "
                    f"defaultValue — and no value is supplied. It is absent from "
                    f"{service}'s application.yaml and there is no {env_key(prop)} on its gitops "
                    f"workload, so SmallRye throws SRCFG00040 and the service does not start. "
                    f"Supply it, give it a defaultValue, or make the field Optional<…>.")

    if required == 0:
        out.append("no required @ConfigProperty was found anywhere — the scan is broken. "
                   "Not reporting a clean run on that.")

    # The empty-value arm reports clean by finding nothing, so it needs its own liveness proof:
    # if the YAML walk stops recognising `${VAR:}` at all, it goes silent instead of red. Every
    # service in this tree carries the four Kafka SSL `${...:}` lines, so zero fleet-wide means
    # the parser broke, not that the fleet is clean.
    if repo == REPO and empty_keys_seen == 0:
        out.append("the defined-as-empty scan matched no `${VAR:}` value in any application.yaml. "
                   "That has never been true of this tree — the YAML walk is broken. Not "
                   "reporting a clean run on that.")

    # Stale in BOTH directions: a baselined empty-value site that no longer matches is a line
    # nobody deleted, and a frozen list that cannot shrink quietly becomes permanent.
    if repo == REPO:
        live = {
            f"{s.relative_to(repo)}::{m.group(1)}"
            for main in gatelib.glob(repo, "openbank-*/src/main")
            if not main.parts[len(repo.parts)].startswith(SKIP_PREFIXES)
            for empty_keys in [empty_valued_keys(main / "resources/application.yaml")]
            for s in gatelib.rglob(main, "*.kt")
            for m in CONFIG_RE.finditer(gatelib.read_text(s, errors="ignore"))
            if m.group(1) in empty_keys and not is_optional(m.group(4))
        }
        for entry in sorted(EMPTY_BASELINE - live):
            out.append(f"{entry} is baselined as a known defined-as-empty injection but no longer "
                       f"matches anything — the defect is gone, delete the line from "
                       f"{EMPTY_BASELINE_FILE.name}.")

    return out, required, empty_shaped, subjects


def selftest() -> int:
    import tempfile

    def service(root: pathlib.Path, decl: str, app_yaml: str) -> None:
        main = root / "openbank-demo/src/main"
        (main / "kotlin").mkdir(parents=True, exist_ok=True)
        (main / "kotlin/C.kt").write_text(decl, encoding="utf-8")
        (main / "resources").mkdir(parents=True, exist_ok=True)
        (main / "resources/application.yaml").write_text(app_yaml, encoding="utf-8")

    required = '@ConfigProperty(name = "openbank.demo.url")\n    lateinit var url: String\n'
    with_default = '@ConfigProperty(name = "openbank.demo.url", defaultValue = "x")\n    var url: String = "x"\n'
    optional = '@ConfigProperty(name = "openbank.demo.url")\n    lateinit var url: Optional<String>\n'
    supplied = "openbank:\n  demo:\n    url: http://x\n"
    profile = "'%prod':\n  openbank:\n    demo:\n      url: http://x\n"
    # A comment naming the property must NOT count — the silent direction.
    comment = "# openbank.demo.url comes from the Deployment env\nquarkus:\n  http:\n    port: 8080\n"
    empty = "quarkus:\n  http:\n    port: 8080\n"

    # #5946 fixtures. `anchor` is the EXACT pre-#5944 AnchorSignerProducer shape, down to the
    # `defaultValue = ""` and the `${VAR:}` binding — the negative control this gate exists for.
    anchor = ('@ConfigProperty(name = "openbank.audit.anchor.kms-key-id", defaultValue = "") '
              'kmsKeyId: String,\n')
    anchor_fixed = ('@ConfigProperty(name = "openbank.audit.anchor.kms-key-id") '
                    'kmsKeyId: Optional<String>,\n')
    anchor_fqn = ('@ConfigProperty(name = "openbank.audit.anchor.kms-key-id") '
                  'kmsKeyId: java.util.Optional<String>,\n')
    anchor_yaml = ("openbank:\n  audit:\n    anchor:\n"
                   "      kms-key-id: ${AUDIT_ANCHOR_KMS_KEY_ID:}\n")
    anchor_yaml_real = ("openbank:\n  audit:\n    anchor:\n"
                        "      kms-key-id: ${AUDIT_ANCHOR_KMS_KEY_ID:alias/audit}\n")
    anchor_yaml_null = "openbank:\n  audit:\n    anchor:\n      kms-key-id:\n"
    # `defaultValue = ""` is NOT a default — measured, SRCFG00014 (see the module docstring). The
    # four real customer-edge beans of this shape stay green only because their gitops workload
    # supplies every one of them as an env var, which the `edge_supplied` fixture stands in for.
    edge = ('@ConfigProperty(name = "openbank.upstream.client-secret", defaultValue = "")\n'
            '    var clientSecret: String = ""\n')
    edge_supplied = "openbank:\n  upstream:\n    client-secret: ${UPSTREAM_SECRET:changeme}\n"
    const_default = ('@ConfigProperty(name = "opa.url", defaultValue = DEFAULT_BASE_URL)\n'
                     '    lateinit var opaUrl: String\n')

    cases = [
        ("required and supplied", required, supplied, 1, 0),
        # -- #5946 --
        ("yaml defines it as empty, target is a plain String — THE #5844 SHAPE",
         anchor, anchor_yaml, 0, 1),
        ("same, expressed as a YAML null value", anchor, anchor_yaml_null, 0, 1),
        ("same property, target made Optional — the #5944 fix", anchor_fixed, anchor_yaml, 0, 0),
        ("Optional written fully-qualified", anchor_fqn, anchor_yaml, 0, 0),
        ("plain String, but the expansion has a real fallback", anchor, anchor_yaml_real, 1, 0),
        # SRCFG00014: an empty defaultValue is not a value, so this is required and unsupplied.
        ("empty defaultValue and supplied nowhere — the SRCFG00014 shape", edge, empty, 1, 1),
        ("empty defaultValue but supplied in yaml — must NOT flag", edge, edge_supplied, 1, 0),
        ("defaultValue given as a named CONSTANT is a real default", const_default, empty, 0, 0),
        ("required and supplied under a profile root", required, profile, 1, 0),
        ("required and NOT supplied — the SRCFG00040 shape", required, empty, 1, 1),
        ("only a COMMENT names it", required, comment, 1, 1),
        ("has a defaultValue", with_default, empty, 0, 0),
        ("is Optional", optional, empty, 0, 0),
    ]
    for label, decl, app_yaml, want_required, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            service(root, decl, app_yaml)
            got, req, _, _ = findings(root)
        if req != want_required:
            print(f"selftest FAIL: {label} — expected {want_required} required property, saw {req}")
            return 1
        # The empty-scan guard adds a finding when nothing was required; discount it.
        real = [f for f in got if "the scan is broken" not in f]
        if len(real) != want:
            print(f"selftest FAIL: {label} — expected {want} finding(s), got {len(real)}: {real}")
            return 1

    with tempfile.TemporaryDirectory() as d:
        got, req, _, _ = findings(pathlib.Path(d))
        if req != 0 or not got:
            print("selftest FAIL: an empty tree did not report that it found nothing.")
            return 1

    print(f"selftest OK: {len(cases)} fixture(s) — supplied, profile-scoped, unsupplied, "
          f"comment-only, defaultValue, Optional, the #5844 defined-as-empty shape and its "
          f"#5944 fix, a YAML null, a real fallback, an FQN Optional, a bare empty "
          f"defaultValue, plus the empty-scan guard.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    found, required, empty_shaped, subjects = findings()
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    print(f"check-configproperty-supplied: {required} required @ConfigProperty declaration(s), "
          f"{empty_shaped} injected from a defined-as-empty yaml value "
          f"({len(EMPTY_BASELINE)} baselined) — "
          f"{'clean.' if not found else f'{len(found)} finding(s) above.'}")
    print(f"SUBJECTS={subjects}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
