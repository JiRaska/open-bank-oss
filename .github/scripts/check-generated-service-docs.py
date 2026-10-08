#!/usr/bin/env python3
"""Check every service convention plugin module after a fleet docs sweep."""

import argparse
import re
from pathlib import Path


SHA = re.compile(r"[0-9a-fA-F]{40}\Z")


def check(root: Path, commit: str) -> int:
    if not SHA.fullmatch(commit):
        raise ValueError("expected a full source commit SHA")
    modules = sorted(
        build.parent
        for build in root.glob("openbank-*/build.gradle.kts")
        if re.search(r'^\s*(?:plugins\s*\{\s*)?id\("openbank\.quarkus-service"\)', build.read_text(), re.MULTILINE)
    )
    if not modules:
        raise ValueError("no service convention plugin modules found")
    for module in modules:
        generated = module / "build/generated/service-docs"
        facts = generated / "docs/00-build.md"
        properties = generated / "openbank-service-build.properties"
        if not facts.is_file() or not properties.is_file():
            raise ValueError(f"{module.name}: generated build facts missing")
        page = facts.read_text()
        version = (module / "version.txt").read_text().strip()
        if not version or f"`{module.name}`" not in page or f"`{version}`" not in page or f"`{commit}`" not in page:
            raise ValueError(f"{module.name}: generated page has wrong module, release version, or source commit")
        if properties.read_text() != f"git.commit={commit}\n":
            raise ValueError(f"{module.name}: generated properties have wrong source commit")
        generated_files = [path for path in generated.rglob("*") if path.is_file()]
        if not generated_files:
            raise ValueError(f"{module.name}: no generated resources")
        for source in generated_files:
            copied = module / "build/resources/main" / source.relative_to(generated)
            if not copied.is_file() or copied.read_bytes() != source.read_bytes():
                raise ValueError(f"{module.name}: {source.relative_to(generated)} was not copied unchanged into resources")
    return len(modules)


def self_test() -> None:
    from tempfile import TemporaryDirectory

    commit = "a" * 40
    with TemporaryDirectory() as tmp:
        root = Path(tmp)
        module = root / "openbank-test-service"
        (module / "src/main/resources").mkdir(parents=True)
        (module / "build.gradle.kts").write_text('plugins {\n    id("openbank.quarkus-service")\n}\n')
        (module / "src/main/resources/openapi.yaml").write_text("openapi: 3.0.0\n")
        (module / "version.txt").write_text("1.2.3\n")
        facts = module / "build/generated/service-docs/docs/00-build.md"
        facts.parent.mkdir(parents=True)
        facts.write_text(f"`openbank-test-service` `1.2.3` `{commit}`\n")
        properties = facts.parent.parent / "openbank-service-build.properties"
        properties.write_text(f"git.commit={commit}\n")
        copied_facts = module / "build/resources/main/docs/00-build.md"
        copied_facts.parent.mkdir(parents=True)
        copied_facts.write_bytes(facts.read_bytes())
        copied_properties = module / "build/resources/main/openbank-service-build.properties"
        copied_properties.write_bytes(properties.read_bytes())
        inline = root / "openbank-inline-service"
        inline.mkdir()
        (inline / "build.gradle.kts").write_text('plugins { id("openbank.quarkus-service") }\n')
        (inline / "version.txt").write_text("1.2.3\n")
        inline_facts = inline / "build/generated/service-docs/docs/00-build.md"
        inline_facts.parent.mkdir(parents=True)
        inline_facts.write_text(f"`openbank-inline-service` `1.2.3` `{commit}`\n")
        inline_properties = inline_facts.parent.parent / "openbank-service-build.properties"
        inline_properties.write_text(f"git.commit={commit}\n")
        inline_copied_facts = inline / "build/resources/main/docs/00-build.md"
        inline_copied_facts.parent.mkdir(parents=True)
        inline_copied_facts.write_bytes(inline_facts.read_bytes())
        (inline / "build/resources/main/openbank-service-build.properties").write_bytes(inline_properties.read_bytes())
        assert check(root, commit) == 2
        copied_facts.write_text("stale copy\n")
        try:
            check(root, commit)
        except ValueError:
            pass
        else:
            raise AssertionError("stale copied resources were accepted")
        copied_facts.write_bytes(facts.read_bytes())
        properties.write_text("git.commit=" + "b" * 40 + "\n")
        try:
            check(root, commit)
        except ValueError:
            pass
        else:
            raise AssertionError("stale generated properties were accepted")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--commit")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not args.commit:
            parser.error("--commit is required")
        print(f"Verified generated build facts for {check(args.root, args.commit)} service modules")
