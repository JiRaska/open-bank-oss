#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""xml-factory-hardened (enforced).

Every JAXP XML factory in production code must come from
`com.openbank.libs.xml.SecureXml` (openbank-libs-domain), which closes every external-entity
vector in one place. A parse site that builds its own factory has to remember half a dozen
settings, and a forgotten one is an XXE: so this fails on ANY raw instantiation of
DocumentBuilderFactory / SAXParserFactory / SchemaFactory / TransformerFactory /
XMLInputFactory / XPathFactory (or XMLReaderFactory.createXMLReader) in a `src/main` Kotlin or
Java file other than SecureXml.kt itself. There is no baseline: the fleet was migrated in the
same change, so the expected count is zero.

Comment lines are ignored (KDoc mentioning a factory is not a parse site).
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

FACTORY = re.compile(
    r"\b(?:DocumentBuilderFactory|SAXParserFactory|SchemaFactory|TransformerFactory|SAXTransformerFactory"
    r"|XMLInputFactory|XPathFactory)\s*\.\s*"
    r"(?:newInstance|newFactory|newDefaultInstance|newDefaultFactory|newNSInstance|newDefaultNSInstance)\b"
    r"|\bXMLReaderFactory\s*\.\s*createXMLReader\b"
)
ALLOWED = {"openbank-libs-domain/src/main/kotlin/com/openbank/libs/xml/SecureXml.kt"}
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "worktrees"}


def sources(root: pathlib.Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        rel_dir = pathlib.Path(dirpath).relative_to(root)
        parts = rel_dir.parts
        if "src" not in parts or parts[parts.index("src") + 1 : parts.index("src") + 2] != ("main",):
            continue
        for name in sorted(filenames):
            if name.endswith((".kt", ".java")):
                yield rel_dir / name, pathlib.Path(dirpath) / name


def check(root: pathlib.Path) -> tuple[list[str], int]:
    findings: list[str] = []
    scanned = 0
    for rel, path in sources(root):
        scanned += 1
        if rel.as_posix() in ALLOWED:
            continue
        for n, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if line.strip().startswith(("//", "*", "/*")):
                continue
            if FACTORY.search(line):
                findings.append(
                    f"{rel}:{n}: raw XML factory — use com.openbank.libs.xml.SecureXml "
                    f"(XXE-hardened) instead: {line.strip()}"
                )
    return findings, scanned


def self_test() -> int:
    ok = True
    cases = [
        ("raw DocumentBuilderFactory in src/main is flagged",
         "svc/src/main/kotlin/A.kt", "val f = DocumentBuilderFactory.newInstance()\n", True),
        ("raw SAXParserFactory in Java is flagged",
         "svc/src/main/java/A.java", "var f = SAXParserFactory.newInstance();\n", True),
        ("SchemaFactory with an argument is flagged",
         "svc/src/main/kotlin/A.kt", "SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)\n", True),
        ("XMLInputFactory.newFactory is flagged",
         "svc/src/main/kotlin/A.kt", "val f = XMLInputFactory.newFactory()\n", True),
        ("a comment is not flagged",
         "svc/src/main/kotlin/A.kt", "// DocumentBuilderFactory.newInstance() is banned\n", False),
        ("src/test is out of scope",
         "svc/src/test/kotlin/A.kt", "val f = DocumentBuilderFactory.newInstance()\n", False),
        ("the hardened helper itself is allowed",
         next(iter(ALLOWED)), "val f = DocumentBuilderFactory.newInstance()\n", False),
        ("SecureXml usage is clean",
         "svc/src/main/kotlin/A.kt", "val d = SecureXml.parse(xml)\n", False),
    ]
    for name, rel, text, expect in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            f = root / rel
            f.parent.mkdir(parents=True)
            f.write_text(text)
            findings, scanned = check(root)
            if bool(findings) != expect or scanned != (0 if "/src/test/" in f"/{rel}" else 1):
                print(f"SELF-TEST FAIL: {name}: findings={findings} scanned={scanned}")
                ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, scanned = check(pathlib.Path(args.root))
    gatelib.subjects(scanned, "src/main Kotlin/Java file(s) scanned for raw XML factories")
    for f in findings:
        print(f"  {f}")
    if findings:
        print(f"{len(findings)} raw XML factory instantiation(s) outside SecureXml")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
