"""Block GitOps image pin changes for services under a governance deploy hold."""

import argparse
import collections
import contextlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

RULES = "openbank-libs/governance/rules.yaml"
GITOPS = "openbank-infra/gitops/"
IMAGE_REF = re.compile(r"^(?:[^\s]+/)?(openbank-[a-z0-9-]+)(?::|@)[^\s]+$")
SERVICE = re.compile(r"^openbank-[a-z0-9-]+$")


class InvalidHold(Exception):
    pass


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=True)
        if key in result:
            raise InvalidHold(f"duplicate YAML key {key!r}")
        result[key] = loader.construct_object(value_node, deep=True)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def holds(text, allow_absent=False):
    try:
        data = yaml.load(text, Loader=UniqueLoader)
    except (yaml.YAMLError, InvalidHold, TypeError) as error:
        raise InvalidHold(f"cannot parse {RULES}: {error}") from error
    if allow_absent and isinstance(data, dict) and "deploy_holds" not in data:
        return {}
    if not isinstance(data, dict) or not isinstance(data.get("deploy_holds"), dict):
        raise InvalidHold("deploy_holds must be a mapping")
    entries = data["deploy_holds"].get("services")
    if not isinstance(entries, dict):
        raise InvalidHold("deploy_holds.services must be a mapping")
    for service, item in entries.items():
        if not isinstance(service, str) or not SERVICE.fullmatch(service):
            raise InvalidHold(f"invalid held service {service!r}")
        constraints(item, service)
    return entries


FIELDS = ("issue", "reason", "release_condition")


def constraints(item, service="hold"):
    """Normalize without interpreting human release conditions or granting authority."""
    if not isinstance(item, dict):
        raise InvalidHold(f"{service}: hold must be a mapping")
    if set(item) == set(FIELDS):
        values = [item]
    elif set(item) == {"all_of"} and isinstance(item["all_of"], list) and item["all_of"]:
        values = item["all_of"]
    else:
        raise InvalidHold(f"{service}: expected legacy triple or nonempty all_of")
    seen = set()
    for value in values:
        if not isinstance(value, dict) or set(value) not in (set(FIELDS), set(FIELDS) | {"origins"}):
            raise InvalidHold(f"{service}: invalid constraint fields")
        if (not isinstance(value["issue"], str)
                or not re.fullmatch(r"#[1-9][0-9]*", value["issue"])
                or any(not isinstance(value[f], str) or not value[f].strip() for f in FIELDS[1:])):
            raise InvalidHold(f"{service}: invalid hold metadata")
        key = tuple(value[f] for f in FIELDS)
        if key in seen:
            raise InvalidHold(f"{service}: duplicate constraint")
        seen.add(key)
        if "origins" in value:
            origins = value["origins"]
            if not isinstance(origins, list) or not origins:
                raise InvalidHold(f"{service}: origins must be nonempty")
            origin_keys = set()
            for origin in origins:
                if (not isinstance(origin, dict) or set(origin) != {"pr", "head"}
                        or type(origin["pr"]) is not int or origin["pr"] <= 0
                        or not isinstance(origin["head"], str)
                        or not re.fullmatch(r"[0-9a-f]{40}", origin["head"])):
                    raise InvalidHold(f"{service}: invalid origin")
                origin_key = (origin["pr"], origin["head"])
                if origin_key in origin_keys:
                    raise InvalidHold(f"{service}: duplicate origin")
                origin_keys.add(origin_key)
    return values


def union_holds(*sources):
    """Exact raw-string union; provenance is evidence, never release approval."""
    merged = {}
    for source in sources:
        for service, item in source.items():
            target = merged.setdefault(service, {})
            for value in constraints(item, service):
                key = tuple(value[f] for f in FIELDS)
                target.setdefault(key, set()).update(
                    (o["pr"], o["head"]) for o in value.get("origins", []))
    result = {}
    for service, values in sorted(merged.items()):
        items = []
        for key, origins in sorted(values.items()):
            value = dict(zip(FIELDS, key))
            if origins:
                value["origins"] = [{"pr": pr, "head": head} for pr, head in sorted(origins)]
            items.append(value)
        result[service] = items[0] if len(items) == 1 and "origins" not in items[0] else {"all_of": items}
    return result


def describe(item):
    return "; ".join(f"{v['issue']} — {v['reason']}. Release condition: {v['release_condition']}"
                     for v in constraints(item))


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=False)
    if result.returncode:
        raise InvalidHold(f"git {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout


def at_base(root, base, path):
    result = subprocess.run(["git", "-C", str(root), "show", f"{base}:{path}"],
                            capture_output=True, check=False)
    if result.returncode:
        # A genuinely new file has no entry in the base tree.
        exists = subprocess.run(["git", "-C", str(root), "cat-file", "-e", f"{base}:{path}"],
                                capture_output=True, check=False)
        if exists.returncode:
            return ""
        raise InvalidHold(f"cannot read {path} at {base}")
    return result.stdout.decode("utf-8")


def pins(text):
    """Resolve YAML aliases before reading images in workload PodSpecs."""
    found = collections.Counter()

    def walk_specs(node):
        if not isinstance(node, dict):
            return
        for key in ("containers", "initContainers", "ephemeralContainers"):
            if key not in node:
                continue
            value = node[key]
            if not isinstance(value, list):
                raise InvalidHold(f"{key} is not a list")
            for container in value:
                if not isinstance(container, dict):
                    raise InvalidHold(f"{key} contains a non-mapping container")
                if "image" in container:
                    image = container["image"]
                    if not isinstance(image, str):
                        raise InvalidHold(f"{key} image is not a string")
                    image = image.strip()
                    match = IMAGE_REF.fullmatch(image)
                    if match:
                        found[(match.group(1), image)] += 1
                    elif "openbank-" in image:
                        raise InvalidHold(f"unclassifiable OpenBank container image {image!r}")
        for key in ("spec", "template", "jobTemplate"):
            walk_specs(node.get(key))

    try:
        for document in yaml.safe_load_all(text):
            if isinstance(document, dict):
                walk_specs(document.get("spec"))
    except yaml.YAMLError as error:
        raise InvalidHold(f"malformed GitOps YAML: {error}") from error
    return found


def validate_services(root, entries, removed_pins=frozenset()):
    needed = set(entries) - set(removed_pins)
    for service in entries:
        if not (root / service / "version.txt").is_file():
            raise InvalidHold(f"{service}: no released module found")
    for path in (root / GITOPS).rglob("*"):
        if not needed:
            break
        if path.suffix not in (".yaml", ".yml"):
            continue
        text = path.read_text(encoding="utf-8")
        if "image:" not in text or not any(service in text for service in needed):
            continue
        needed.difference_update(service for service, _ in pins(text))
    if needed:
        raise InvalidHold(f"held service(s) without GitOps image pin: {', '.join(sorted(needed))}")


def filter_tags(root, raw):
    entries = holds((root / RULES).read_text(encoding="utf-8"))
    validate_services(root, entries)
    try:
        tags = json.loads(raw)
    except json.JSONDecodeError as error:
        raise InvalidHold(f"invalid image tag map: {error}") from error
    if not isinstance(tags, dict) or any(not isinstance(k, str) or not isinstance(v, str)
                                          for k, v in tags.items()):
        raise InvalidHold("image tag map must be a string-to-string object")
    for service in sorted(entries.keys() & tags.keys()):
        print(f"::notice::{service} held by {describe(entries[service])}; leaving its image pin unchanged. "
              "Scheduled reconcile will re-offer it after the hold is removed.", file=sys.stderr)
    return {service: tag for service, tag in tags.items() if service not in entries}


def changed_held_pins(root, base, held):
    output = git(root, "diff", "--name-status", "-z", "-M", base, "HEAD", "--", GITOPS).decode()
    if not output:
        return []
    fields = iter(output.rstrip("\0").split("\0"))
    violations = []
    for status in fields:
        old_path = next(fields, None)
        if old_path is None:
            raise InvalidHold("malformed git name-status diff")
        path = next(fields, None) if status.startswith(("R", "C")) else old_path
        if path is None:
            raise InvalidHold("malformed git rename/copy diff")
        if not old_path.endswith((".yaml", ".yml")) and not path.endswith((".yaml", ".yml")):
            continue
        before = pins("" if status.startswith("C") else at_base(root, base, old_path))
        after_path = root / path
        after = pins(after_path.read_text(encoding="utf-8") if after_path.exists() else "")
        for service in sorted(held):
            old = {tag: count for (name, tag), count in before.items() if name == service}
            new = {tag: count for (name, tag), count in after.items() if name == service}
            if old != new:
                violations.append((service, path, old, new))
    return violations


def check(root, base):
    head = holds((root / RULES).read_text(encoding="utf-8"))
    # A PR must not remove a hold and change its image in the same diff. The hold
    # release is reviewed and merged first; a successor PR may then advance the pin.
    previous = holds(at_base(root, base, RULES), allow_absent=True)
    held = union_holds(previous, head)
    violations = changed_held_pins(root, base, held)
    validate_services(root, held, {service for service, _, _, _ in violations})
    for service, path, old, new in violations:
        issue = describe(held[service])
        print(f"::error file={path}::{service} deploy held by {issue}; image pin changed "
              f"{old} -> {new}.")
    print(f"deploy holds: {len(held)} service(s), {len(violations)} blocked pin change(s)")
    return 1 if violations else 0


def strict_required_checks(rules):
    """A held deploy needs GitHub to invalidate green checks after main moves."""
    if not isinstance(rules, list):
        raise InvalidHold("live branch rules response is not a list")
    status_rules = [rule for rule in rules if isinstance(rule, dict)
                    and rule.get("type") == "required_status_checks"]
    if not status_rules:
        raise InvalidHold("main has no live required-status-checks rule")
    return all(isinstance(rule.get("parameters"), dict)
               and rule["parameters"].get("strict_required_status_checks_policy") is True
               for rule in status_rules)


def require_live_strict(root, base, repo):
    current = holds((root / RULES).read_text(encoding="utf-8"))
    previous = holds(at_base(root, base, RULES), allow_absent=True) if base else {}
    if not (current or previous):
        print("deploy holds: none; live strict check not needed")
        return
    try:
        result = subprocess.run(["gh", "api", f"repos/{repo}/rules/branches/main"],
                                capture_output=True, text=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise InvalidHold(f"cannot read live main protection: {error}") from error
    if result.returncode:
        raise InvalidHold(f"cannot read live main protection: gh api exited {result.returncode}")
    try:
        live_rules = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise InvalidHold("live main protection response is not JSON") from error
    if not strict_required_checks(live_rules):
        raise InvalidHold("deploy hold requires strict up-to-date checks on main before it can merge; "
                          "an older green auto-deploy PR would otherwise remain mergeable")
    print("deploy hold: live main protection requires current-base checks")


def self_test():
    # run-gates gives self-tests a scratch index for the real checkout. The
    # fixture is its own repository and must use its own index.
    os.environ.pop("GIT_INDEX_FILE", None)
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.name", "Gate Test"], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.email", "gate@example.invalid"], check=True)
        rule = root / RULES
        rule.parent.mkdir(parents=True)
        rule.write_text("deploy_holds:\n  services:\n    openbank-sanctions-service:\n"
                        "      issue: '#11492'\n      reason: review\n"
                        "      release_condition: fixed image and reviews verified\n")
        manifest = root / GITOPS / "components" / "mixed.yaml"
        manifest.parent.mkdir(parents=True)
        original = ("kind: Pod\nspec:\n  containers:\n"
                    "    - image: registry/openbank-sanctions-service:sandbox-aaaaaaaa\n"
                    "    - image: registry/openbank-ledger-service:sandbox-aaaaaaaa\n")
        manifest.write_text(original)
        (root / "openbank-sanctions-service").mkdir()
        (root / "openbank-sanctions-service" / "version.txt").write_text("1.0.0\n")
        subprocess.run(["git", "-C", str(root), "add", RULES, str(manifest.relative_to(root))], check=True)
        subprocess.run(["git", "-C", str(root), "-c", "commit.gpgsign=false",
                        "commit", "-qm", "base"], check=True)
        base = git(root, "rev-parse", "HEAD").decode().strip()
        tags = '{"openbank-sanctions-service":"sandbox-bbbbbbbb",' \
               '"openbank-ledger-service":"sandbox-bbbbbbbb"}'
        assert filter_tags(root, tags) == {
                                     "openbank-ledger-service": "sandbox-bbbbbbbb"}
        rule.write_text("deploy_holds:\n  services: {}\n")
        assert filter_tags(root, tags) == json.loads(tags)  # eligible on next reconcile
        rule.write_text("deploy_holds:\n  services:\n    openbank-typo-service:\n"
                        "      issue: '#11492'\n      reason: typo\n      release_condition: reviewed\n")
        try:
            filter_tags(root, tags)
        except InvalidHold:
            pass
        else:
            raise AssertionError("unknown held service passed")
        subprocess.run(["git", "-C", str(root), "checkout", "--", RULES], check=True)

        def verify(content, expected):
            manifest.write_text(content)
            subprocess.run(["git", "-C", str(root), "add", str(manifest.relative_to(root))], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "commit.gpgsign=false",
                            "commit", "-qm", "pin"], check=True)
            with contextlib.redirect_stdout(io.StringIO()):
                assert check(root, base) == expected
            subprocess.run(["git", "-C", str(root), "reset", "--hard", "-q", base], check=True)

        verify(original.replace("openbank-ledger-service:sandbox-aaaaaaaa",
                                "openbank-ledger-service:sandbox-bbbbbbbb"), 0)
        verify(original.replace("openbank-sanctions-service:sandbox-aaaaaaaa",
                                "openbank-sanctions-service:sandbox-bbbbbbbb"), 1)
        verify(original.replace("sandbox-aaaaaaaa", "sandbox-bbbbbbbb"), 1)
        verify(original.replace("    - image: registry/openbank-sanctions-service:sandbox-aaaaaaaa\n", ""), 1)
        verify("metadata:\n  annotations:\n    image: registry/openbank-sanctions-service:sandbox-aaaaaaaa\n"
               "candidate: &candidate registry/openbank-sanctions-service:sandbox-bbbbbbbb\n"
               "kind: Pod\nspec:\n  containers:\n    - image: *candidate\n", 1)
        verify("metadata:\n  annotations:\n    image: registry/openbank-sanctions-service:sandbox-aaaaaaaa\n"
               "kind: Pod\nspec:\n  containers:\n    - image: >-\n"
               "        registry/openbank-sanctions-service:sandbox-bbbbbbbb\n", 1)
        renamed = manifest.with_name("renamed.yaml")
        subprocess.run(["git", "-C", str(root), "mv", str(manifest.relative_to(root)),
                        str(renamed.relative_to(root))], check=True)
        subprocess.run(["git", "-C", str(root), "-c", "commit.gpgsign=false",
                        "commit", "-qm", "rename manifest"], check=True)
        with contextlib.redirect_stdout(io.StringIO()):
            assert check(root, base) == 0  # same held pin at a new path
        subprocess.run(["git", "-C", str(root), "reset", "--hard", "-q", base], check=True)
        renamed.unlink(missing_ok=True)
        manifest.write_text(original.replace("openbank-sanctions-service:sandbox-aaaaaaaa",
                                            "openbank-sanctions-service:sandbox-bbbbbbbb"))
        rule.write_text("deploy_holds:\n  services: {}\n")
        subprocess.run(["git", "-C", str(root), "add", RULES, str(manifest.relative_to(root))], check=True)
        subprocess.run(["git", "-C", str(root), "-c", "commit.gpgsign=false",
                        "commit", "-qm", "remove hold and pin"], check=True)
        with contextlib.redirect_stdout(io.StringIO()):
            assert check(root, base) == 1
        subprocess.run(["git", "-C", str(root), "reset", "--hard", "-q", base], check=True)
        try:
            holds(rule.read_text().replace("      reason: review\n", "      reason: review\n      reason: duplicate\n"))
        except InvalidHold:
            pass
        else:
            raise AssertionError("duplicate hold key passed")
        try:
            holds("deploy_holds: []\n")
        except InvalidHold:
            pass
        else:
            raise AssertionError("malformed hold passed")
        strict_rule = {"type": "required_status_checks", "parameters": {
            "required_status_checks": [{"context": "Validate manifests"}],
            "strict_required_status_checks_policy": True}}
        assert strict_required_checks([strict_rule])
        assert not strict_required_checks([{**strict_rule, "parameters": {
            **strict_rule["parameters"], "strict_required_status_checks_policy": False}}])
        try:
            strict_required_checks([])
        except InvalidHold:
            pass
        else:
            raise AssertionError("missing live status-check rule passed")
        from unittest.mock import patch
        good = subprocess.CompletedProcess([], 0, stdout=json.dumps([strict_rule]), stderr="")
        with patch("subprocess.run", return_value=good):
            require_live_strict(root, None, "example/repo")
        bad_rule = {**strict_rule, "parameters": {
            **strict_rule["parameters"], "strict_required_status_checks_policy": False}}
        bad = subprocess.CompletedProcess([], 0, stdout=json.dumps([bad_rule]), stderr="")
        with patch("subprocess.run", return_value=bad):
            try:
                require_live_strict(root, None, "example/repo")
            except InvalidHold:
                pass
            else:
                raise AssertionError("active hold passed without live strict checks")
        rule.write_text("deploy_holds:\n  services: {}\n")
        with patch("subprocess.run", side_effect=AssertionError("empty hold queried GitHub")):
            require_live_strict(root, None, "example/repo")
    print("deploy hold self-test passed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--base", default=os.environ.get("PR_DIFF_BASE"))
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--filter-tags", action="store_true",
                        help="read build image-tag JSON on stdin; emit only unheld services")
    parser.add_argument("--list-holds", action="store_true",
                        help="emit held services for the pre-cap reconcile filter")
    parser.add_argument("--rules-ref", help="read hold policy from this Git ref (live main)")
    parser.add_argument("--enforce-live-strict", action="store_true",
                        help="require live strict main protection while a hold is proposed or active")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss"))
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
            return 0
        if args.filter_tags:
            print(json.dumps(filter_tags(args.root.resolve(), sys.stdin.read()), separators=(",", ":")))
            return 0
        if args.list_holds:
            if not args.rules_ref:
                raise InvalidHold("--list-holds requires --rules-ref")
            entries = holds(git(args.root.resolve(), "show", f"{args.rules_ref}:{RULES}").decode())
            validate_services(args.root.resolve(), entries)
            print(" ".join(sorted(entries)))
            return 0
        if not args.base:
            if os.environ.get("GITHUB_EVENT_NAME") == "push":
                entries = holds((args.root / RULES).read_text(encoding="utf-8"))
                print(f"deploy holds: {len(entries)} service(s); push run has no PR diff")
                if args.enforce_live_strict:
                    require_live_strict(args.root.resolve(), None, args.repo)
                return 0
            raise InvalidHold("--base or PR_DIFF_BASE is required")
        result = check(args.root.resolve(), args.base)
        if args.enforce_live_strict:
            require_live_strict(args.root.resolve(), args.base, args.repo)
        return result
    except (InvalidHold, OSError, UnicodeError) as error:
        print(f"::error::deploy hold check could not validate: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
