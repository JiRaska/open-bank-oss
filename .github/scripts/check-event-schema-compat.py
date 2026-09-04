#!/usr/bin/env python3
"""schema-compat gate — event backward-compatibility (rules.yaml event_change, ADR-0006/0048).

The fleet's event contract is the set of Kotlin data classes extending
`DomainEvent`, serialized as JSON onto Kafka via the transactional outbox
(there are no .avsc files today; Apicurio/Avro is the ADR-0006 target state).
A consumer replaying historical events, or an old consumer reading new
events, breaks when a producer edits an event class incompatibly.

DIFF-SCOPED: for every changed Kotlin file that declares `... : DomainEvent(`
(at base or head), compare each event data class's primary-constructor
properties between the PR base and HEAD and report:

    removed property            breaking — consumers reading it get null/fail
    property type changed       breaking — deserialization of old/new payloads diverges
    new property, no default    breaking on REPLAY — historical events lack the
      and non-nullable            field; deserializer cannot construct the class
    eventType literal changed   breaking — consumer routing keys off eventType

Compatible evolutions (new nullable property, new property with a default)
pass silently. A breaking change is legitimate ONLY as a new versioned event
(new class + `-vN` topic per ADR-0006) — never an in-place edit.

Also covers *.avsc files if/when they appear (ADR-0006 target state):
added fields must carry a "default", removed fields must have had one,
in-place type changes are flagged.

Also covers */schema/*.schema.json files (ADR-0260: JSON Schema is the fleet's
chosen event-schema format, not Avro). Each file may be a single object schema
or a `oneOf` list of branches distinguished by an `x-openbank-event-type`
keyword (or `title`, for a topic whose branches carry no explicit event-type
tag) -- the shape a topic needs when its events are discriminated only by the
Kafka `ce-type` header (ADR-0260 D4), which this comparator does not itself
verify against real header literals (see check-event-contract-code-agreement.py
and check-asyncapi-doc-discriminator.py for that half). Within each branch,
`properties`/`required` are compared the same way as a DomainEvent's
constructor: a removed property, a type change, or a property that becomes
required is breaking; a new optional property is compatible. THIS PATH IS THE
ONLY compatibility check for a producer whose event class does not extend
DomainEvent -- e.g. openbank-document-service's DocumentGenerated and
SignatureCeremonyCompleted, which the DomainEvent-scoped comparator above has
never once evaluated (the same structural gap SepaPaymentCreatedEvent has on
the money-path side). A JSON-Schema-covered topic closes that gap; an
uncovered one does not, which is exactly why ADR-0260's pilot registers one.

stdlib-only. Advisory by default (::warning, exit 0); --enforce exits 1.

Usage:
    check-event-schema-compat.py --base <sha> [--enforce]
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

# DomainEvent as a supertype: matches `: DomainEvent(`, `, DomainEvent(` (interface listed
# first) and a multiline supertype list. `\b` keeps it from matching `AbstractDomainEvent(`.
EVENT_MARKER_RE = re.compile(r"\bDomainEvent\s*\(")


def git_show(base: str, path: str) -> str | None:
    res = subprocess.run(["git", "show", f"{base}:{path}"], capture_output=True, text=True)
    return res.stdout if res.returncode == 0 else None


def changed_files(base: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", base, "HEAD"], capture_output=True, text=True, check=True
    ).stdout
    return [line for line in out.splitlines() if line.strip()]


def strip_comments(src: str) -> str:
    """Remove Kotlin block and line comments, preserving string literals.

    Why this exists: a KDoc INSIDE a constructor parameter list contains commas, and
    split_params splits on top-level commas. Measured 2026-09-04 on
    AccountEvents.kt::AccountCreatedEvent — 9 declared properties, the parser captured 8, and
    split_params produced 15 fragments instead of 9 because the KDoc above the last parameter
    was diced into pieces. The final fragment read
    `* the same spelling ... */ val sourceService: String`, which PARAM_RE cannot match, so
    `sourceService` was invisible to the gate — and REMOVING it therefore produced NO finding,
    which is the one thing this gate exists to catch.

    Fleet-wide that was 15 properties across 4 files, every one of them the `sourceService`
    field on money-path account/transaction events.

    Kotlin block comments NEST, so the scanner counts depth rather than searching for the
    first `*/` (this repo has been burnt by that before).
    """
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        if ch == '"':
            if src.startswith('\"\"\"', i):
                end = src.find('\"\"\"', i + 3)
                end = n if end == -1 else end + 3
            else:
                end = i + 1
                while end < n and src[end] != '"':
                    end += 2 if src[end] == "\\" else 1
                end = min(end + 1, n)
            out.append(src[i:end]); i = end; continue
        if src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j): depth += 1; j += 2; continue
                if src.startswith("*/", j): depth -= 1; j += 2; continue
                j += 1
            out.append(" "); i = j; continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            out.append(" "); i = n if j == -1 else j; continue
        out.append(ch); i += 1
    return "".join(out)


def split_params(paramlist: str) -> list[str]:
    """Split a Kotlin parameter list on top-level commas (generics/parens/brace aware).

    Tracks (), [], {} and <> for generics. `>` is only treated as a generic close when depth
    is positive AND it isn't part of an arrow `->` (lambda type), so a lambda-typed or
    comparison-defaulted param cannot drive depth negative and silently merge later params.
    """
    parts: list[str] = []
    depth = 0
    cur: list[str] = []
    prev = ""
    for ch in paramlist:
        if ch in "(<[{":
            # only count `<` as a generic-open (it's also the less-than operator, but that
            # doesn't appear in a well-formed ctor param type); parens/brackets/braces always.
            depth += 1
        elif ch in ")]}":
            depth = max(0, depth - 1)
        elif ch == ">" and prev != "-" and depth > 0:
            depth = max(0, depth - 1)  # generic close, but not the `>` of an arrow `->`
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        prev = ch
    tail = "".join(cur).strip()
    if tail:
        parts.append(tail)
    return parts


PARAM_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"          # annotations
    r"(?:override\s+)?(?:val|var)\s+"
    r"(?P<name>\w+)\s*:\s*"
    r"(?P<type>[^=]+?)\s*"
    r"(?P<default>=.+)?$",
    re.DOTALL,
)


def parse_events(text: str) -> dict[str, dict]:
    """Map event class name -> {props: {name: (type, has_default)}, event_type: str|None}."""
    events: dict[str, dict] = {}
    for m in re.finditer(r"data class\s+(\w+)\s*\(", text):
        name = m.group(1)
        # capture the balanced parameter list
        i = m.end()
        depth = 1
        while i < len(text) and depth:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        paramlist = strip_comments(text[m.end(): i - 1])
        # an event class is `data class X(...) : Iface?, DomainEvent(...) { ... }` — the marker
        # must appear in the supertype list, i.e. between the ctor's closing paren and the class
        # body's opening brace. Search that whole span (not a fixed 80-char window) so a class
        # with marker interfaces before DomainEvent, or a multiline supertype list, isn't skipped.
        body_open = text.find("{", i)
        supertypes = text[i: body_open if body_open != -1 else i + 200]
        if not EVENT_MARKER_RE.search(supertypes):
            continue
        props: dict[str, tuple[str, bool]] = {}
        for raw in split_params(paramlist):
            raw = " ".join(raw.split())  # collapse whitespace/newlines
            pm = PARAM_RE.match(raw)
            if not pm:
                continue
            ptype = " ".join(pm.group("type").split())
            props[pm.group("name")] = (ptype, pm.group("default") is not None)
        # eventType literal in the class body (best effort). Scan to the next data-class
        # declaration (or EOF) rather than a fixed 600-char window, so a large KDoc before the
        # `override val eventType` line doesn't hide it.
        event_type = None
        if body_open != -1:
            nxt = text.find("data class", body_open + 1)
            body = text[body_open: nxt if nxt != -1 else len(text)]
            em = re.search(r"eventType\s*=\s*\"([^\"]+)\"", body)
            if em:
                event_type = em.group(1)
        events[name] = {"props": props, "event_type": event_type}
    return events


def compare_events(path: str, old: dict[str, dict], new: dict[str, dict]) -> list[str]:
    findings: list[str] = []
    for cls, o in old.items():
        n = new.get(cls)
        if n is None:
            findings.append(
                f"{path}: event class {cls} was removed/renamed — breaking for consumers; "
                f"ship a new versioned event and deprecate the old one (ADR-0006)"
            )
            continue
        for prop, (otype, _) in o["props"].items():
            if prop not in n["props"]:
                findings.append(
                    f"{path}: {cls}.{prop} removed — breaking (consumers/replay still read it); "
                    f"a breaking change must be a NEW versioned event, not an in-place edit"
                )
            elif n["props"][prop][0] != otype:
                findings.append(
                    f"{path}: {cls}.{prop} type changed {otype} -> {n['props'][prop][0]} — "
                    f"breaking for deserialization of historical payloads"
                )
        for prop, (ptype, has_default) in n["props"].items():
            if prop in o["props"]:
                continue
            nullable = ptype.rstrip().endswith("?")
            if not has_default and not nullable:
                findings.append(
                    f"{path}: {cls}.{prop} added as non-nullable {ptype} without a default — "
                    f"breaking on REPLAY (historical events lack the field); make it nullable "
                    f"or defaulted, or version the event"
                )
        if o["event_type"] and n["event_type"] and o["event_type"] != n["event_type"]:
            findings.append(
                f"{path}: {cls}.eventType changed \"{o['event_type']}\" -> \"{n['event_type']}\" — "
                f"breaking for consumer routing"
            )
    return findings


def avro_fields(schema: dict) -> dict[str, dict]:
    return {f["name"]: f for f in schema.get("fields", []) if isinstance(f, dict) and "name" in f}


def compare_avsc(path: str, old_text: str, new_text: str) -> list[str]:
    findings: list[str] = []
    try:
        old, new = json.loads(old_text), json.loads(new_text)
    except json.JSONDecodeError as e:
        return [f"{path}: unparseable Avro schema JSON ({e})"]
    of, nf = avro_fields(old), avro_fields(new)
    for name, f in of.items():
        if name not in nf:
            if "default" not in f:
                findings.append(
                    f"{path}: Avro field {name} removed without a writer default — breaking for readers"
                )
        elif json.dumps(nf[name].get("type"), sort_keys=True) != json.dumps(f.get("type"), sort_keys=True):
            findings.append(f"{path}: Avro field {name} type changed — verify schema-resolution compatibility")
    for name, f in nf.items():
        if name not in of and "default" not in f:
            findings.append(
                f"{path}: Avro field {name} added without a default — old payloads cannot be read (breaking)"
            )
    return findings


def json_schema_branches(schema: dict) -> dict[str, dict]:
    """Map an event-type key -> its JSON Schema branch.

    A single-shape topic is one implicit branch (the whole document). A topic whose events are
    discriminated by the `ce-type` Kafka header (ADR-0260 D4) -- so the body carries no field
    that tells them apart -- expresses that as a top-level `oneOf`, keyed here by each branch's
    `x-openbank-event-type` (falling back to `title`, then a positional key so a malformed branch
    still participates instead of vanishing silently).
    """
    raw = schema.get("oneOf")
    branches = raw if isinstance(raw, list) else [schema]
    out: dict[str, dict] = {}
    for i, b in enumerate(branches):
        if not isinstance(b, dict):
            continue
        key = b.get("x-openbank-event-type") or b.get("title") or f"branch[{i}]"
        out[key] = b
    return out


def compare_json_schema(path: str, old_text: str, new_text: str) -> list[str]:
    try:
        old, new = json.loads(old_text), json.loads(new_text)
    except json.JSONDecodeError as e:
        return [f"{path}: unparseable JSON Schema ({e})"]
    findings: list[str] = []
    old_branches, new_branches = json_schema_branches(old), json_schema_branches(new)
    for key, ob in old_branches.items():
        nb = new_branches.get(key)
        if nb is None:
            findings.append(
                f"{path}: event type {key!r} removed from the schema — breaking for consumers; "
                f"ship a new versioned event, don't delete a oneOf branch in place (ADR-0006/ADR-0260)"
            )
            continue
        oprops = ob.get("properties") if isinstance(ob.get("properties"), dict) else {}
        nprops = nb.get("properties") if isinstance(nb.get("properties"), dict) else {}
        oreq = set(ob.get("required")) if isinstance(ob.get("required"), list) else set()
        nreq = set(nb.get("required")) if isinstance(nb.get("required"), list) else set()
        for prop, ospec in oprops.items():
            if prop not in nprops:
                findings.append(
                    f"{path}: {key}.{prop} removed — breaking (consumers reading it get nothing); "
                    f"a breaking change must be a NEW versioned event, not an in-place edit"
                )
                continue
            otype = ospec.get("type") if isinstance(ospec, dict) else None
            nspec = nprops[prop]
            ntype = nspec.get("type") if isinstance(nspec, dict) else None
            if json.dumps(otype, sort_keys=True) != json.dumps(ntype, sort_keys=True):
                findings.append(
                    f"{path}: {key}.{prop} type changed {otype!r} -> {ntype!r} — breaking for "
                    f"deserialization of historical payloads"
                )
        for prop in nprops:
            if prop in nreq and prop not in oprops:
                findings.append(
                    f"{path}: {key}.{prop} added as a REQUIRED property — breaking on REPLAY "
                    f"(historical events lack the field); make it optional or version the event"
                )
        for prop in nreq - oreq:
            if prop in oprops:
                findings.append(
                    f"{path}: {key}.{prop} changed from optional to required — breaking on "
                    f"REPLAY (historical events may lack the field)"
                )
    return findings


def self_test() -> int:
    """Falsify the Kotlin event parser and both compatibility comparators.

    ADR-0006: an event on the wire is a contract with every consumer AND with every message
    already in the topic. A removed property, a changed type, or a new required field breaks
    REPLAY — historical payloads stop deserializing — and the failure surfaces at consumer
    start-up, or worse during a replay months later, never in the PR that caused it.

    Every rule below has a lenient direction that reports clean, which is the one that ships.
    """
    fails: list[str] = []

    def case(label, findings, want_hit, want_sub=""):
        got = bool(findings)
        if got != want_hit:
            fails.append(f"{label}: expected finding={want_hit}, got {findings}")
        elif want_sub and not any(want_sub in f for f in findings):
            fails.append(f"{label}: flagged for the wrong reason — no {want_sub!r} in {findings}")

    def ev(body: str) -> dict:
        return parse_events(body)

    base = 'data class Paid(val id: String, val amount: Long) : DomainEvent()\n'
    old = ev(base)
    if "Paid" not in old or set(old["Paid"]["props"]) != {"id", "amount"}:
        fails.append(f"the parser did not read the baseline event: {old}")

    # Identical is compatible — without this case a comparator that flags everything looks
    # exactly like a working one.
    case("an unchanged event is compatible", compare_events("p", old, ev(base)), False)

    # THE BREAKING SET. Each is invisible until a consumer or a replay hits it.
    case("a removed property is breaking",
         compare_events("p", old, ev('data class Paid(val id: String) : DomainEvent()\n')),
         True, "removed")
    case("a changed type is breaking",
         compare_events("p", old, ev('data class Paid(val id: String, val amount: String) : DomainEvent()\n')),
         True, "type changed")
    case("a removed event class is breaking",
         compare_events("p", old, ev('data class Other(val id: String) : DomainEvent()\n')),
         True, "removed/renamed")

    # ADDITIVE rules. A new field is only safe if old payloads can still deserialize — which
    # means nullable, or defaulted. Getting this wrong in the lenient direction ships a
    # required field into a topic full of messages that lack it.
    case("a new REQUIRED non-nullable field is breaking",
         compare_events("p", old, ev('data class Paid(val id: String, val amount: Long, val fee: Long) : DomainEvent()\n')),
         True)
    case("a new NULLABLE field is compatible",
         compare_events("p", old, ev('data class Paid(val id: String, val amount: Long, val fee: Long?) : DomainEvent()\n')),
         False)
    case("a new DEFAULTED field is compatible",
         compare_events("p", old, ev('data class Paid(val id: String, val amount: Long, val fee: Long = 0) : DomainEvent()\n')),
         False)

    # --- Avro, the other wire format ------------------------------------------------------
    import json as _json
    a_old = _json.dumps({"type": "record", "name": "Paid",
                         "fields": [{"name": "id", "type": "string"}]})
    case("an unchanged avsc is compatible", compare_avsc("s.avsc", a_old, a_old), False)
    case("a removed avro field is breaking",
         compare_avsc("s.avsc", a_old, _json.dumps({"type": "record", "name": "Paid", "fields": []})),
         True)
    a_new_req = _json.dumps({"type": "record", "name": "Paid", "fields": [
        {"name": "id", "type": "string"}, {"name": "fee", "type": "long"}]})
    case("a new avro field with no default is breaking", compare_avsc("s.avsc", a_old, a_new_req), True)
    a_new_def = _json.dumps({"type": "record", "name": "Paid", "fields": [
        {"name": "id", "type": "string"}, {"name": "fee", "type": "long", "default": 0}]})
    case("a new avro field WITH a default is compatible",
         compare_avsc("s.avsc", a_old, a_new_def), False)

    # A class that is not a DomainEvent must not be parsed as one — otherwise every ordinary
    # data class in the diff becomes an event contract and the gate is unusable.
    if parse_events('data class NotAnEvent(val x: String)\n'):
        fails.append("a plain data class was parsed as a DomainEvent")

    # --- JSON Schema, the ADR-0260 format, and the gap it closes ---------------------------
    # DocumentGenerated (openbank-document-service) is a plain data class with NO DomainEvent
    # supertype — parse_events must not see it at all, exactly like SepaPaymentCreatedEvent on
    # the money-path side. That is the case for compare_json_schema to prove it closes.
    doc_generated_kt = (
        'data class DocumentGenerated(\n'
        '    val documentId: UUID,\n'
        '    val templateCode: String,\n'
        '    val templateVersion: String,\n'
        '    val sha256: String,\n'
        '    val occurredAt: Instant,\n'
        ')\n'
    )
    if parse_events(doc_generated_kt):
        fails.append(
            "DocumentGenerated (no DomainEvent supertype) was parsed as an event — the "
            "DomainEvent-scoped comparator should be structurally blind to it"
        )
    dg_findings = compare_events(
        "DocumentEvents.kt", parse_events(doc_generated_kt),
        parse_events(doc_generated_kt.replace("val sha256: String,\n", "")),
    )
    if dg_findings:
        fails.append(
            f"the DomainEvent comparator found something in a class it should never parse: {dg_findings}"
        )

    doc_schema_old = _json.dumps({
        "oneOf": [{
            "x-openbank-event-type": "document.generated.v1",
            "properties": {
                "documentId": {"type": "string", "format": "uuid"},
                "templateCode": {"type": "string"},
                "templateVersion": {"type": "string"},
                "sha256": {"type": "string"},
                "occurredAt": {"type": "string", "format": "date-time"},
            },
            "required": ["documentId", "templateCode", "templateVersion", "sha256", "occurredAt"],
        }],
    })
    case("an unchanged JSON Schema oneOf branch is compatible",
         compare_json_schema("s.schema.json", doc_schema_old, doc_schema_old), False)

    # THE case this self-test exists for: removing `sha256` from document-event.schema.json is
    # exactly the same defect class as removing it from DocumentGenerated's constructor — and
    # the DomainEvent comparator (proven above) can never see it, because the class carries no
    # DomainEvent supertype. Only compare_json_schema can catch this breaking change.
    doc_schema_sha_removed = _json.dumps({
        "oneOf": [{
            "x-openbank-event-type": "document.generated.v1",
            "properties": {
                "documentId": {"type": "string", "format": "uuid"},
                "templateCode": {"type": "string"},
                "templateVersion": {"type": "string"},
                "occurredAt": {"type": "string", "format": "date-time"},
            },
            "required": ["documentId", "templateCode", "templateVersion", "occurredAt"],
        }],
    })
    case("removing sha256 from document-event.schema.json is breaking — the gap the "
         "DomainEvent comparator has for this exact class (no DomainEvent supertype)",
         compare_json_schema("s.schema.json", doc_schema_old, doc_schema_sha_removed),
         True, "sha256 removed")

    doc_schema_type_changed = json.loads(doc_schema_old)
    doc_schema_type_changed["oneOf"][0]["properties"]["sha256"] = {"type": "integer"}
    case("a JSON Schema property type change is breaking",
         compare_json_schema("s.schema.json", doc_schema_old, _json.dumps(doc_schema_type_changed)),
         True, "type changed")

    doc_schema_new_required = json.loads(doc_schema_old)
    doc_schema_new_required["oneOf"][0]["properties"]["signerCount"] = {"type": "integer"}
    doc_schema_new_required["oneOf"][0]["required"].append("signerCount")
    case("a new REQUIRED JSON Schema property is breaking on replay",
         compare_json_schema("s.schema.json", doc_schema_old, _json.dumps(doc_schema_new_required)),
         True, "REQUIRED")

    doc_schema_new_optional = json.loads(doc_schema_old)
    doc_schema_new_optional["oneOf"][0]["properties"]["signerCount"] = {"type": "integer"}
    case("a new OPTIONAL JSON Schema property is compatible",
         compare_json_schema("s.schema.json", doc_schema_old, _json.dumps(doc_schema_new_optional)),
         False)

    doc_schema_branch_removed = _json.dumps({"oneOf": []})
    case("a removed oneOf branch (event type deleted) is breaking",
         compare_json_schema("s.schema.json", doc_schema_old, doc_schema_branch_removed),
         True, "removed from the schema")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: event schema compatibility is falsifiable (17 cases)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    findings: list[str] = []
    for path in changed_files(args.base):
        if path.endswith(".avsc"):
            old_text = git_show(args.base, path)
            try:
                new_text = open(path, encoding="utf-8").read()
            except OSError:
                continue  # deleted schema — reviewed as a service/event removal
            if old_text is not None:
                findings.extend(compare_avsc(path, old_text, new_text))
            continue
        if path.endswith(".schema.json") and "/schema/" in path:
            old_text = git_show(args.base, path)
            try:
                new_text = open(path, encoding="utf-8").read()
            except OSError:
                continue  # deleted schema — reviewed as a service/event removal
            if old_text is not None:
                findings.extend(compare_json_schema(path, old_text, new_text))
            continue
        if not (path.endswith(".kt") and "/src/main/" in path):
            continue
        old_text = git_show(args.base, path)
        try:
            new_text = open(path, encoding="utf-8").read()
        except OSError:
            new_text = None
        if new_text is None:
            if old_text and EVENT_MARKER_RE.search(old_text):
                findings.append(
                    f"{path}: file with event classes deleted — breaking unless the events are "
                    f"retired with their consumers (verify in review)"
                )
            continue
        if not EVENT_MARKER_RE.search(old_text or "") and not EVENT_MARKER_RE.search(new_text):
            continue
        findings.extend(compare_events(path, parse_events(old_text or ""), parse_events(new_text)))

    level = "error" if args.enforce else "warning"
    for f in findings:
        print(f"::{level}::schema-compat gate: {f}")
    if not findings:
        print("schema-compat gate: no backward-incompatible event/schema change detected.")
        return 0
    if args.enforce:
        return 1
    print(
        f"schema-compat gate: {len(findings)} finding(s) — advisory until the ADR-0144 "
        "target_enforce_date; a breaking event change ships as a NEW versioned event (ADR-0006)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
