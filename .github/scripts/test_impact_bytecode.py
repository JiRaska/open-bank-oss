#!/usr/bin/env python3
"""Retain unverified JVM class references without claiming source impact.

Class-file identity and hashes bind the observed bytes, but do not prove that
cached bytecode was compiled from the checked-out sources. Mapping remains
unknown until a separate compile/input attestation exists.
"""

from __future__ import annotations

import hashlib
import re
import struct
from pathlib import Path


def class_refs_and_source(data: bytes) -> tuple[set[str], str | None, str] | None:
    """Read JVM constants, SourceFile and this_class; malformed files stay unknown."""
    try:
        offset = 0

        def take(size: int) -> bytes:
            nonlocal offset
            if offset + size > len(data):
                raise ValueError("truncated class file")
            value = data[offset:offset + size]
            offset += size
            return value

        def u1() -> int:
            return take(1)[0]

        def u2() -> int:
            return struct.unpack(">H", take(2))[0]

        def u4() -> int:
            return struct.unpack(">I", take(4))[0]

        if u4() != 0xCAFEBABE:
            return None
        take(4)  # minor and major versions
        pool: list[tuple[int, object] | None] = [None]
        pool_count = u2()
        while len(pool) < pool_count:
            tag = u1()
            if tag == 1:
                pool.append((tag, take(u2()).decode("utf-8", errors="replace")))
            elif tag in {3, 4}:
                take(4)
                pool.append((tag, None))
            elif tag in {5, 6}:
                take(8)
                pool.extend(((tag, None), None))
            elif tag in {7, 8, 16, 19, 20}:
                pool.append((tag, u2()))
            elif tag in {9, 10, 11, 12, 17, 18}:
                take(4)
                pool.append((tag, None))
            elif tag == 15:
                take(3)
                pool.append((tag, None))
            else:
                return None

        def utf8(index: int) -> str:
            item = pool[index]
            if item is None or item[0] != 1:
                raise ValueError("invalid UTF-8 pool reference")
            return str(item[1])

        refs = {utf8(int(item[1])) for item in pool[1:] if item is not None and item[0] == 7}
        take(2)  # access
        self_entry = pool[u2()]
        if self_entry is None or self_entry[0] != 7:
            return None
        self_name = utf8(int(self_entry[1]))
        take(2)  # super_class
        take(2 * u2())  # interfaces

        def skip_attributes() -> None:
            for _ in range(u2()):
                take(2)
                take(u4())

        for _ in range(u2()):
            take(6)  # field access/name/descriptor
            skip_attributes()
        for _ in range(u2()):
            take(6)  # method access/name/descriptor
            skip_attributes()
        source = None
        for _ in range(u2()):
            name = utf8(u2())
            length = u4()
            value = take(length)
            if name == "SourceFile" and length == 2:
                source = utf8(struct.unpack(">H", value)[0])
        return refs, source, self_name
    except (IndexError, ValueError, struct.error):
        return None


def checked_source(service: Path, relative: str) -> str | None:
    if not re.fullmatch(r"src/main/(?:kotlin|java)/[A-Za-z0-9_./-]+\.(?:kt|java)", relative):
        return None
    if any(part in {".", ".."} for part in Path(relative).parts):
        return None
    root = service.resolve()
    source = service / relative
    if not source.is_file() or not source.resolve().is_relative_to(root):
        return None
    return relative


def checked_class_file(service: Path, path: Path) -> bool:
    root = service.resolve()
    return (path.is_file() and (service / "build/classes").resolve().is_relative_to(root)
            and path.resolve().is_relative_to((root / "build/classes")))


def direct_bytecode_mapping(service: Path, cases: list[dict]) -> dict:
    """Return unverified class references; never infer a test-to-source mapping."""
    main_roots = [service / f"build/classes/{language}/main" for language in ("kotlin", "java")]
    test_roots = [service / f"build/classes/{language}/test" for language in ("kotlin", "java")]
    if not cases or not any(root.is_dir() for root in main_roots) or not any(root.is_dir() for root in test_roots):
        return {"schemaVersion": 1, "mode": "shadow", "mappingState": "unknown", "selectionState": "unavailable"}

    production: dict[str, dict] = {}
    ambiguous: set[str] = set()
    for root in main_roots:
        if not root.is_dir():
            continue
        for class_file in root.rglob("*.class"):
            if not checked_class_file(service, class_file):
                continue
            class_bytes = class_file.read_bytes()
            parsed = class_refs_and_source(class_bytes)
            if parsed is None or parsed[1] is None:
                continue
            internal = class_file.relative_to(root).with_suffix("").as_posix()
            if parsed[2] != internal:
                continue
            package = internal.rpartition("/")[0]
            candidate = None
            for language in ("kotlin", "java"):
                path = f"src/main/{language}/{package}/{parsed[1]}" if package else f"src/main/{language}/{parsed[1]}"
                found = checked_source(service, path)
                if found is not None:
                    if candidate is not None and candidate != found:
                        candidate = None
                        break
                    candidate = found
            if candidate is None:
                continue
            evidence = {
                "referencedClass": internal,
                "productionClassSha256": hashlib.sha256(class_bytes).hexdigest(),
            }
            if internal in production and production[internal] != evidence:
                ambiguous.add(internal)
            else:
                production[internal] = evidence
    for internal in ambiguous:
        production.pop(internal, None)

    references = []
    unique_cases = {case["fingerprint"]: case for case in cases}
    for fingerprint, case in sorted(unique_cases.items()):
        internal = case["classname"].replace(".", "/")
        edges: dict[str, dict] = {}
        for root in test_roots:
            class_file = root / f"{internal}.class"
            if not checked_class_file(service, class_file):
                continue
            class_bytes = class_file.read_bytes()
            parsed = class_refs_and_source(class_bytes)
            definition = case.get("testDefinitionPath")
            if (parsed is None or parsed[2] != internal or not isinstance(definition, str)
                    or parsed[1] != Path(definition).name
                    or not re.fullmatch(r"src/test/(?:kotlin|java)/[A-Za-z0-9_./-]+\.(?:kt|java)", definition)
                    or any(part in {".", ".."} for part in Path(definition).parts)
                    or not (service / definition).is_file()
                    or not (service / definition).resolve().is_relative_to(service.resolve())):
                continue
            test_sha = hashlib.sha256(class_bytes).hexdigest()
            for ref in parsed[0]:
                if ref in production:
                    item = {**production[ref], "testClassSha256": test_sha}
                    edges[item["referencedClass"]] = item
        references.append({"fingerprint": fingerprint, "state": "unverified" if edges else "unknown",
                           "directClassRefs": [edges[key] for key in sorted(edges)]})

    with_refs = sum(item["state"] == "unverified" for item in references)
    return {
        "schemaVersion": 2, "mode": "shadow", "mappingState": "unknown",
        "selectionState": "unavailable", "evidenceState": "unverified-bytecode-references",
        "method": "jvm-bytecode-direct-class-reference", "references": references,
        "coverage": {"observedTests": len(references), "testsWithUnverifiedRefs": with_refs,
                     "unknownTests": len(references) - with_refs},
    }
