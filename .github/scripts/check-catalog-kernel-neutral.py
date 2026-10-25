#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The product-catalog kernel carries no industry vocabulary (ADR-0257, #13429).

ADR-0257 says the generic kernel "contains no bank, insurance or telecom enum" and that industry
packs contribute types, schemas and validators as DATA. Prose did not hold: a pension approval
workflow (role enum, schema-id branches, pension columns, a pension resource) landed inside the
kernel and every gate stayed green. This check makes the boundary decidable.

Default-deny: every Kotlin file under the service's ``src/main/kotlin`` is KERNEL unless
``rules.yaml: catalog_kernel_neutrality.non_kernel_files`` names it with a reason. In a kernel file:

1. ``enum class`` is allowed only for a structural enum listed in ``structural_enums`` -- one the
   kernel's own code branches on (lifecycle, price mechanics, operators).
2. No string literal may name a pack schema id (``org.openbank.<pack>.<type>``).
3. No identifier or literal may carry pack vocabulary. The vocabulary is DERIVED from
   ``src/main/resources/catalog-packs/<pack>/<type>-v<N>.schema.json`` -- pack names plus the words
   of each type name -- never hand-kept, so a new pack extends the check by existing.

The v2 wire contract is held to the same rule: in ``openapi.yaml`` every ``/api/v2`` path,
operationId and component schema/property carrying pack vocabulary must be ``deprecated: true``.
A deprecated wire name still has to be mapped in kernel code during its compatibility window;
``deprecated_wire_aliases`` names those identifiers, each with an ``expires`` date.

Outside the kernel, the legacy ``/api/v1`` enums are FROZEN: ``legacy_v1_frozen_enums`` pins each
one's exact value set, so adding a value fails (the v1 vocabulary is a compatibility boundary
with a sunset, not a place to grow). A ``non_kernel_files`` entry with ``expires`` fails after
that date, so a deprecated alias cannot become permanent.
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
import tempfile

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
RULES = ROOT / "openbank-libs/governance/rules.yaml"

ENUM_RE = re.compile(r"\benum\s+class\s+(\w+)\s*(?:\([^)]*\))?\s*\{([^}]*)\}", re.S)
STRING_RE = re.compile(r'"(?:[^"\\\n]|\\.)*"')
IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
SCHEMA_FILE_RE = re.compile(r"^(?P<type>[a-z0-9-]+)-v\d+\.schema\.json$")


def strip_comments(src: str) -> str:
    """Drop // and /* */ comments but keep string literals (a KDoc may cite an ADR's pack)."""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(src[i : j + 1])
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            seg = src[i : (n if j < 0 else j + 2)]
            out.append("\n" * seg.count("\n"))
            i = n if j < 0 else j + 2
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def words(identifier: str) -> list[str]:
    """camelCase / snake_case / kebab-case / dotted -> lower-case words."""
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", identifier)
    return [w.lower() for w in re.split(r"[^A-Za-z0-9]+", spaced) if w]


def pack_vocabulary(packs_dir: pathlib.Path, ignored: set[str]) -> tuple[set[str], set[str]]:
    """(vocabulary words, schema ids) derived from the pack directory itself."""
    vocab: set[str] = set()
    schema_ids: set[str] = set()
    for schema in sorted(packs_dir.glob("*/*.schema.json")):
        match = SCHEMA_FILE_RE.match(schema.name)
        if not match:
            continue
        pack, type_name = schema.parent.name, match["type"]
        schema_ids.add(f"org.openbank.{pack}.{type_name}")
        vocab.add(pack)
        vocab.update(w for w in type_name.split("-") if w)
    return vocab - ignored, schema_ids


def line_of(src: str, offset: int) -> int:
    return src.count("\n", 0, offset) + 1


def check(service: pathlib.Path, cfg: dict, today: dt.date) -> list[str]:
    kotlin_root = service / "src/main/kotlin"
    packs_dir = service / "src/main/resources/catalog-packs"
    findings: list[str] = []

    vocab, schema_ids = pack_vocabulary(packs_dir, set(cfg.get("vocabulary_not_industry", [])))
    if not vocab:
        return [f"{packs_dir}: no pack schemas found -- the vocabulary probe cannot run (refusing to pass)"]

    structural = set(cfg.get("structural_enums", []))
    frozen = {k: list(v) for k, v in (cfg.get("legacy_v1_frozen_enums") or {}).items()}
    non_kernel: dict[str, dict] = {}
    for entry in cfg.get("non_kernel_files", []):
        non_kernel[entry["path"]] = entry
        if not entry.get("reason"):
            findings.append(f"rules.yaml non_kernel_files {entry['path']}: a reason is required")
        expires = entry.get("expires")
        if expires and dt.date.fromisoformat(str(expires)) < today:
            findings.append(
                f"rules.yaml non_kernel_files {entry['path']}: expired {expires} -- remove the alias "
                f"or re-justify it ({entry.get('tracking', 'no tracking issue')})"
            )

    aliases: set[str] = set()
    for entry in cfg.get("deprecated_wire_aliases", []):
        aliases.add(entry["name"])
        if dt.date.fromisoformat(str(entry["expires"])) < today:
            findings.append(
                f"rules.yaml deprecated_wire_aliases {entry['name']}: expired {entry['expires']} -- "
                f"remove the alias ({entry.get('tracking', 'no tracking issue')})"
            )

    files = sorted(kotlin_root.rglob("*.kt"))
    for rel in non_kernel:
        if not (kotlin_root / rel).exists():
            findings.append(f"rules.yaml non_kernel_files {rel}: no such file (stale entry)")

    seen_frozen: set[str] = set()
    for path in files:
        rel = path.relative_to(kotlin_root).as_posix()
        raw = path.read_text(encoding="utf-8")
        src = strip_comments(raw)
        for match in ENUM_RE.finditer(src):
            name = match[1]
            values = [v.strip().split("(")[0].strip() for v in match[2].split(";")[0].split(",")]
            values = [v for v in values if v]
            where = f"{service.name}/src/main/kotlin/{rel}:{line_of(src, match.start())}"
            if name in frozen:
                seen_frozen.add(name)
                if values != frozen[name]:
                    findings.append(
                        f"{where}: legacy v1 enum {name} is FROZEN (ADR-0257 amendment) -- values "
                        f"{values} != pinned {frozen[name]}. New vocabulary belongs in a pack schema."
                    )
                continue
            if rel not in non_kernel and name not in structural:
                findings.append(
                    f"{where}: enum class {name} in the catalog kernel. Kernel enums must be "
                    f"structural (rules.yaml catalog_kernel_neutrality.structural_enums); business "
                    f"vocabulary belongs in a pack schema or pack.json."
                )
        if rel in non_kernel:
            continue
        for match in STRING_RE.finditer(src):
            literal = match.group(0)[1:-1]
            where = f"{service.name}/src/main/kotlin/{rel}:{line_of(src, match.start())}"
            hit = next((sid for sid in schema_ids if sid in literal), None)
            if hit:
                findings.append(f"{where}: kernel names pack schema {hit!r} -- branch on pack data instead")
                continue
            bad = sorted(set(words(literal)) & vocab)
            if bad:
                findings.append(f"{where}: kernel literal {match.group(0)} carries pack vocabulary {bad}")
        code_only = STRING_RE.sub('""', src)
        for match in IDENT_RE.finditer(code_only):
            if match.group(0) in aliases:
                continue
            bad = sorted(set(words(match.group(0))) & vocab)
            if bad:
                where = f"{service.name}/src/main/kotlin/{rel}:{line_of(code_only, match.start())}"
                findings.append(f"{where}: kernel identifier {match.group(0)} carries pack vocabulary {bad}")

    findings.extend(check_openapi(service / "src/main/resources/openapi.yaml", vocab))

    for name in sorted(set(frozen) - seen_frozen):
        findings.append(f"rules.yaml legacy_v1_frozen_enums {name}: enum no longer exists (stale entry)")
    return findings


def v2_schemas(spec: dict) -> set[str]:
    """Component schemas reachable from an /api/v2 path (v1-only schemas are the frozen boundary)."""
    schemas = (spec.get("components") or {}).get("schemas") or {}
    components = spec.get("components") or {}

    def refs(node) -> list[str]:
        found: list[str] = []
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "$ref" and isinstance(value, str):
                    found.append(value)
                else:
                    found.extend(refs(value))
        elif isinstance(node, list):
            for value in node:
                found.extend(refs(value))
        return found

    pending = [r for p, item in (spec.get("paths") or {}).items() if str(p).startswith("/api/v2") for r in refs(item)]
    seen_refs: set[str] = set()
    reached: set[str] = set()
    while pending:
        ref = pending.pop()
        if ref in seen_refs or not ref.startswith("#/components/"):
            continue
        seen_refs.add(ref)
        _, _, kind, name = ref.split("/", 3)
        target = (components.get(kind) or {}).get(name)
        if kind == "schemas" and name in schemas:
            reached.add(name)
        pending.extend(refs(target))
    return reached


def check_openapi(spec_path: pathlib.Path, vocab: set[str]) -> list[str]:
    """v2 names carrying pack vocabulary must be deprecated (the wire is part of the kernel)."""
    if not spec_path.exists():
        return []
    spec = yaml.safe_load(spec_path.read_text(encoding="utf-8")) or {}
    findings: list[str] = []
    where = spec_path.relative_to(ROOT) if spec_path.is_relative_to(ROOT) else spec_path

    def vocab_in(name: str) -> list[str]:
        return sorted(set(words(name)) & vocab)

    for path, item in (spec.get("paths") or {}).items():
        if not str(path).startswith("/api/v2"):
            continue
        for method, op in (item or {}).items():
            if not isinstance(op, dict):
                continue
            names = [path, op.get("operationId", "")]
            bad = sorted({w for n in names for w in vocab_in(str(n))})
            if bad and not op.get("deprecated"):
                findings.append(f"{where}: {method.upper()} {path} carries pack vocabulary {bad} and is not deprecated")
    schemas = (spec.get("components") or {}).get("schemas") or {}
    for name in sorted(v2_schemas(spec)):
        schema = schemas.get(name)
        if not isinstance(schema, dict) or schema.get("deprecated"):
            continue
        if vocab_in(name):
            findings.append(f"{where}: schema {name} carries pack vocabulary {vocab_in(name)} and is not deprecated")
        for prop, body in (schema.get("properties") or {}).items():
            if vocab_in(prop) and not (isinstance(body, dict) and body.get("deprecated")):
                findings.append(
                    f"{where}: {name}.{prop} carries pack vocabulary {vocab_in(prop)} and is not deprecated"
                )
    return findings


def load_cfg() -> dict:
    rules = yaml.safe_load(RULES.read_text(encoding="utf-8"))
    return rules["catalog_kernel_neutrality"]


def self_test() -> int:
    """Known-positive and known-negative, built in a scratch tree."""
    cfg = {
        "structural_enums": ["RevisionState"],
        "vocabulary_not_industry": ["v"],
        "legacy_v1_frozen_enums": {"CardTier": ["STANDARD", "GOLD"]},
        "non_kernel_files": [
            {"path": "legacy/Product.kt", "reason": "v1"},
            {"path": "rest/OldAlias.kt", "reason": "alias", "expires": "2000-01-01"},
        ],
    }
    cases = {
        "clean": ({"kernel/K.kt": "enum class RevisionState { DRAFT }\nval x = \"approvals\"\n"}, 0),
        "industry enum": ({"kernel/K.kt": "enum class ApprovalRole { A, B }\n"}, 1),
        "schema id literal": ({"kernel/K.kt": 'val s = "org.openbank.retirement.pension-savings"\n'}, 1),
        "vocabulary identifier": ({"kernel/K.kt": "val pensionDigest: String? = null\n"}, 1),
        "vocabulary only in comment": ({"kernel/K.kt": "// pension pack, ADR-0334\nval a = 1\n"}, 0),
        "frozen enum grew": ({"legacy/Product.kt": "enum class CardTier { STANDARD, GOLD, BLACK }\n"}, 1),
        "frozen enum unchanged": ({"legacy/Product.kt": "enum class CardTier { STANDARD, GOLD }\n"}, 0),
    }
    failures = 0
    for label, (files, expected_min) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            svc = pathlib.Path(tmp) / "svc"
            (svc / "src/main/resources/catalog-packs/retirement").mkdir(parents=True)
            (svc / "src/main/resources/catalog-packs/retirement/pension-savings-v2.schema.json").write_text("{}")
            base = {"legacy/Product.kt": "enum class CardTier { STANDARD, GOLD }\n", "rest/OldAlias.kt": ""}
            for rel, body in {**base, **files}.items():
                target = svc / "src/main/kotlin" / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(body)
            found = [f for f in check(svc, cfg, dt.date(2026, 1, 1)) if "expired" not in f]
            expired = [f for f in check(svc, cfg, dt.date(2026, 1, 1)) if "expired" in f]
            ok = (len(found) >= 1) if expected_min else (not found)
            ok = ok and len(expired) == 1
            print(f"{'ok  ' if ok else 'FAIL'} {label}: {len(found)} finding(s)")
            failures += 0 if ok else 1
    for label, deprecated, expected in (("v2 path with vocabulary", False, 1), ("deprecated v2 alias", True, 0)):
        with tempfile.TemporaryDirectory() as tmp:
            svc = pathlib.Path(tmp) / "svc"
            (svc / "src/main/resources/catalog-packs/retirement").mkdir(parents=True)
            (svc / "src/main/resources/catalog-packs/retirement/pension-savings-v2.schema.json").write_text("{}")
            (svc / "src/main/kotlin/legacy").mkdir(parents=True)
            (svc / "src/main/kotlin/legacy/Product.kt").write_text("enum class CardTier { STANDARD, GOLD }\n")
            (svc / "src/main/kotlin/rest").mkdir(parents=True)
            (svc / "src/main/kotlin/rest/OldAlias.kt").write_text("")
            op = {"operationId": "listPensionThings", "responses": {}}
            if deprecated:
                op["deprecated"] = True
            spec = {"paths": {"/api/v2/x/pension-things": {"get": op}, "/api/v1/pension": {"get": {}}}}
            (svc / "src/main/resources/openapi.yaml").write_text(yaml.safe_dump(spec))
            found = [f for f in check(svc, cfg, dt.date(2026, 1, 1)) if "openapi.yaml" in f]
            ok = len(found) == expected
            print(f"{'ok  ' if ok else 'FAIL'} {label}: {len(found)} finding(s)")
            failures += 0 if ok else 1
    with tempfile.TemporaryDirectory() as tmp:
        svc = pathlib.Path(tmp) / "svc"
        (svc / "src/main/kotlin").mkdir(parents=True)
        empty = check(svc, cfg, dt.date(2026, 1, 1))
        ok = any("refusing to pass" in f for f in empty)
        print(f"{'ok  ' if ok else 'FAIL'} missing pack dir refuses to pass")
        failures += 0 if ok else 1
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    cfg = load_cfg()
    service = ROOT / cfg.get("service", "openbank-product-catalog")
    findings = check(service, cfg, dt.date.today())
    for finding in findings:
        print(finding)
    kotlin = len(list((service / "src/main/kotlin").rglob("*.kt")))
    print(f"catalog-kernel-neutral: {len(findings)} finding(s) across {kotlin} Kotlin files")
    print(f"SUBJECTS={kotlin}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
