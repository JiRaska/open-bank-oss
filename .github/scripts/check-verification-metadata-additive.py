#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Accept a regenerated gradle/verification-metadata.xml only if it is purely additive.

The Dependabot metadata refresh is split in two (dependabot-verification-metadata.yml):
an unprivileged pull_request job regenerates the file by resolving the bumped graph, and
a privileged workflow_run job pushes it. The regenerated file is therefore UNTRUSTED
input to the privileged job: the build that produced it ran the bump's own plugins.

What a hostile or broken regeneration could do, and what this refuses:

- change `<configuration>` (turn off `verify-metadata`, add `<trusted-artifacts>`,
  ignore keys) -- that disables verification for everything, so it must be identical;
- change the checksum of an artifact the base already pins -- that is exactly the
  tamper signal dependency verification exists to raise, never something to refresh;
- not be verification metadata at all (wrong root, unparseable).

Added components, added artifacts and dropped entries are allowed: a bump adds the new
version's artifacts and the old version's may fall out of the resolved graph.

Usage:
    check-verification-metadata-additive.py BASE.xml CANDIDATE.xml
    check-verification-metadata-additive.py --selftest
Exit 0 accepted, 1 refused, 2 usage/unreadable.
"""

import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

NS = "{https://schema.gradle.org/dependency-verification}"
XSI = "{http://www.w3.org/2001/XMLSchema-instance}"


# The only shapes Gradle writes under <components>. Anything else is refused rather than
# ignored: a validator that skips what it does not recognise is a parser differential,
# because Gradle may read the very element this script stepped over.
COMPONENT_TAGS = {"component", "artifact", "sha1", "sha256", "sha512", "md5", "also-trust", "pgp"}
DIGEST_TAGS = {"sha1", "sha256", "sha512", "md5", "pgp"}


def load(path: Path) -> ET.Element:
    text = path.read_text(encoding="utf-8")
    # Untrusted input: Gradle never writes a DTD, so refuse any rather than rely on the
    # parser's entity limits (entity expansion, external entities).
    if "<!DOCTYPE" in text or "<!ENTITY" in text:
        raise ValueError(f"{path}: carries a DTD or entity declaration")
    root = ET.fromstring(text)
    if root.tag != NS + "verification-metadata":
        raise ValueError(f"{path}: root is {root.tag!r}, not verification-metadata")
    for element in root.iter():
        if not isinstance(element.tag, str) or not element.tag.startswith(NS):
            raise ValueError(f"{path}: element outside the verification namespace: {element.tag!r}")
        for attribute in element.attrib:
            # Gradle writes xsi:schemaLocation on the root; nowhere else carries a namespace.
            if attribute.startswith("{") and not (element is root and attribute.startswith(XSI)):
                raise ValueError(f"{path}: namespaced attribute {attribute!r}")
    children = [child.tag.removeprefix(NS) for child in root]
    if children.count("configuration") != 1 or children.count("components") > 1 or (
            set(children) - {"configuration", "components"}):
        raise ValueError(f"{path}: root must hold one <configuration> and at most one <components>, got {children}")
    components = root.find(NS + "components")
    if components is not None:
        for element in components.iter():
            if element is not components and element.tag.removeprefix(NS) not in COMPONENT_TAGS:
                raise ValueError(f"{path}: unexpected <{element.tag.removeprefix(NS)}> under <components>")
    return root


def configuration(root: ET.Element) -> str:
    node = root.find(NS + "configuration")
    if node is None:
        raise ValueError("no <configuration> element")
    return ET.canonicalize(ET.tostring(node, encoding="unicode"), strip_text=True)


def checksums(root: ET.Element) -> dict[tuple[str, str, str, str], frozenset[tuple[str, str]]]:
    pins: dict[tuple[str, str, str, str], frozenset[tuple[str, str]]] = {}
    components = root.find(NS + "components")
    if components is None:
        return pins
    for component in components:
        if component.tag != NS + "component":
            raise ValueError(f"<{component.tag.removeprefix(NS)}> directly under <components>")
        coordinate = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        for artifact in component:
            if artifact.tag != NS + "artifact":
                raise ValueError(f"<{artifact.tag.removeprefix(NS)}> directly under <component>")
            key = (*coordinate, artifact.get("name", ""))
            # A repeated key would let a second entry carry a value the comparison never sees.
            if key in pins:
                raise ValueError("artifact listed more than once: " + ":".join(key))
            values = set()
            for digest in artifact:
                tag = digest.tag.removeprefix(NS)
                if tag not in DIGEST_TAGS:
                    raise ValueError(f"<{tag}> directly under <artifact>")
                values.add((tag, digest.get("value", "")))
                for also in digest:
                    if also.tag != NS + "also-trust":
                        raise ValueError(f"<{also.tag.removeprefix(NS)}> inside a digest")
                    values.add((tag, also.get("value", "")))
            pins[key] = frozenset(values)
    return pins


def refusals(base: ET.Element, candidate: ET.Element) -> list[str]:
    problems = []
    if configuration(base) != configuration(candidate):
        problems.append("<configuration> differs from the base; the refresh may only add checksums")
    old, new = checksums(base), checksums(candidate)
    for key in sorted(old.keys() & new.keys()):
        if old[key] != new[key]:
            problems.append("checksum changed for an already pinned artifact: " + ":".join(key))
    return problems


def check(base_path: Path, candidate_path: Path) -> int:
    try:
        base, candidate = load(base_path), load(candidate_path)
        problems = refusals(base, candidate)
        added = len(checksums(candidate).keys() - checksums(base).keys())
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"::error::verification metadata could not be checked: {error}")
        return 2
    for problem in problems:
        print(f"::error::{problem}")
    if problems:
        return 1
    print(f"verification metadata is additive: {added} artifact(s) added, no pin or configuration changed")
    return 0


def selftest() -> int:
    head = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">\n'
            '<configuration><verify-metadata>true</verify-metadata>'
            '<verify-signatures>false</verify-signatures></configuration>\n<components>\n')
    tail = "</components>\n</verification-metadata>\n"

    def doc(*artifacts: tuple[str, str], config: str = "") -> str:
        body = "".join(
            f'<component group="g" name="{name}" version="1"><artifact name="{name}-1.jar">'
            f'<sha256 value="{value}" origin="Generated by Gradle"/></artifact></component>\n'
            for name, value in artifacts)
        text = head + body + tail
        return text.replace("<verify-signatures>false</verify-signatures>", config) if config else text

    base = doc(("a", "11"))
    cases = [
        ("identical", base, 0),
        ("one artifact added", doc(("a", "11"), ("b", "22")), 0),
        ("one artifact dropped", doc(("b", "22")), 0),
        ("reindented only", base.replace("\n", "\n   "), 0),
        ("pinned checksum changed", doc(("a", "99")), 1),
        ("verification switched off", doc(("a", "11"), config=(
            "<verify-signatures>false</verify-signatures><trusted-artifacts>"
            '<trust group=".*" regex="true"/></trusted-artifacts>')), 1),
        ("verify-metadata false", base.replace("<verify-metadata>true", "<verify-metadata>false"), 1),
        ("not metadata", "<project/>", 2),
        ("not xml", "<verification-metadata", 2),
        ("second configuration", base.replace("<components>", "<configuration><verify-metadata>false"
                                               "</verify-metadata></configuration>\n<components>"), 2),
        ("foreign-namespace element", base.replace("<components>", '<components><trusted-artifacts xmlns="urn:x">'
                                                    '<trust group=".*"/></trusted-artifacts>'), 2),
        ("unknown element under components", base.replace("<components>", "<components><ignored-key/>"), 2),
        ("duplicate artifact with a second value", doc(("a", "11"), ("a", "99")), 2),
        ("unexpected root child", base.replace("</verification-metadata>", "<keyring-format/></verification-metadata>"), 2),
        ("namespaced attribute below root", base.replace('<component group="g"',
                                                          '<component xmlns:q="urn:q" q:x="1" group="g"'), 2),
        ("entity declaration", '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e "x">]>' + base.split("?>", 1)[1], 2),
    ]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        base_path = Path(tmp, "base.xml")
        base_path.write_text(base, encoding="utf-8")
        for name, text, expected in cases:
            candidate = Path(tmp, "candidate.xml")
            candidate.write_text(text, encoding="utf-8")
            got = check(base_path, candidate)
            if got != expected:
                failed += 1
                print(f"SELFTEST FAIL: {name}: expected {expected}, got {got}", file=sys.stderr)
    if failed:
        return 1
    print(f"self-test ok: {len(cases)} cases, including a changed pin and disabled verification")
    return 0


def main(argv: list[str]) -> int:
    if argv == ["--selftest"]:
        return selftest()
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    return check(Path(argv[0]), Path(argv[1]))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
