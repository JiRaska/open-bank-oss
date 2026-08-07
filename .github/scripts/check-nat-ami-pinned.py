#!/usr/bin/env python3
"""Guard: the NAT instance's AMI is PINNED, so no plain `tofu apply` can rebuild egress.

WHY THIS EXISTS (issue #3602)
    `openbank-infra/aws/modules/network` runs the private subnets' egress through a single
    fck-nat EC2 instance (ADR-0058). Its AMI came straight out of a `most_recent = true`
    `aws_ami` data source:

        data "aws_ami" "fck_nat" { most_recent = true  ... }
        resource "aws_instance" "fck_nat" { ami = data.aws_ami.fck_nat[0].id }

    `ami` is a replace-forcing attribute, and that data source re-resolves on EVERY plan. So
    every time the upstream publisher shipped a patched AMI — no commit, no review, no author —
    the plan silently re-armed with:

        ~ ami = "ami-08c439a446e724124" -> "ami-0899dbf7e367fdbb4" # forces replacement

    and dragged `aws_route_table.private` along with it, because the default route points at
    the instance's primary ENI. The next person to apply this stack for ANY unrelated reason
    (a one-line IAM fix is how it was found) destroyed and recreated the single NAT and dropped
    all private-subnet egress. Nothing was wrong with the state; the ordinary command was the
    landmine, and the person most likely to step on it is the one doing the obviously-correct
    thing.

    The class is general and not specific to NAT: an instance whose AMI is resolved rather than
    declared has a replace scheduled by a third party on a timetable nobody in this repo sets.
    On a singleton that IS the egress path, that is an outage waiting for an unrelated apply.

WHAT IT CHECKS

    R1 — no `aws_instance` under openbank-infra/aws/ may take its `ami` from a
         `most_recent = true` aws_ami data source WITHOUT a pin variable in the same
         expression. The shipped shape is the conditional

             ami = var.nat_ami_id != "" ? var.nat_ami_id : data.aws_ami.fck_nat[0].id

         where the data source is the BOOTSTRAP fallback for a greenfield environment that has
         no instance yet, and every live environment pins. Deleting the pin half puts the
         landmine straight back, and R1 is what notices.

    R2 — every env module block that sets `egress_mode = "fck_nat"` must also pass a concrete
         `nat_ami_id = "ami-..."`. R1 alone cannot see this: the module keeps a legal unpinned
         fallback path, so an environment that simply never sets the variable is unpinned while
         every file it references looks correct. R2 is the half that makes "unpinned" a
         property of the environment rather than of the module.

    Both rules read HCL with comments STRIPPED, because this file's own subject matter is the
    forbidden shape: the prose above and the comments in `modules/network/main.tf` both contain
    a literal `ami = data.aws_ami...` and a literal `most_recent = true`. A whole-file grep
    cannot tell the thing from the prose about the thing, and would flag the very fix it exists
    to protect.

WHAT IT DELIBERATELY DOES NOT CHECK
    That the pinned id is the newest available, or that it is the one currently running. Both
    need an AWS call, and neither is a property of the repo — the point of a pin is that the
    two are ALLOWED to diverge until a human decides otherwise. The upgrade path is a one-line
    PR; see `var.nat_ami_id` for the lookup command.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

AWS_DIR = pathlib.Path("openbank-infra/aws")
ENVS_DIR = AWS_DIR / "envs"

AMI_ID_RE = re.compile(r'^"ami-[0-9a-f]{8,17}"$')
DATA_AWS_AMI_RE = re.compile(r"\bdata\.aws_ami\.([A-Za-z0-9_-]+)\b")
VAR_REF_RE = re.compile(r"\bvar\.[A-Za-z0-9_-]+\b")


def strip_comments(text: str) -> str:
    """Remove HCL comments (# , // , /* */) without touching string literals.

    Comment-stripping is not cosmetic here — see the module docstring. Newlines are preserved
    so reported line numbers still match the file on disk.
    """
    out: list[str] = []
    i, n = 0, len(text)
    in_string = False
    while i < n:
        ch = text[i]
        if in_string:
            if ch == "\\" and i + 1 < n:
                out.append(text[i : i + 2])
                i += 2
                continue
            if ch == '"':
                in_string = False
            out.append(ch)
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "#" or text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            chunk = text[i:] if end == -1 else text[i : end + 2]
            out.append("\n" * chunk.count("\n"))
            i = n if end == -1 else end + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def find_blocks(text: str, header_re: re.Pattern[str]) -> list[tuple[re.Match[str], str, int]]:
    """Return (header match, block body, 1-based line of the header) for each matching block."""
    blocks = []
    for match in header_re.finditer(text):
        start = text.find("{", match.end() - 1)
        if start == -1:
            continue
        depth, i, n = 0, start, len(text)
        in_string = False
        while i < n:
            ch = text[i]
            if in_string:
                if ch == "\\":
                    i += 2
                    continue
                if ch == '"':
                    in_string = False
            elif ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        blocks.append((match, text[start + 1 : i], text.count("\n", 0, match.start()) + 1))
    return blocks


def argument_value(body: str, name: str) -> str | None:
    """The right-hand side of a top-level `name = ...` argument, or None if unset.

    Single-line expressions only: every argument this gate reads is one, and a multi-line
    expression that silently read as absent would be a false PASS. So an argument whose value
    looks continued is returned verbatim rather than dropped — the caller then judges the text.
    """
    pattern = re.compile(rf"^[ \t]*{re.escape(name)}[ \t]*=[ \t]*(.+)$", re.MULTILINE)
    match = pattern.search(body)
    return match.group(1).strip() if match else None


def scan(root: pathlib.Path) -> list[str]:
    findings: list[str] = []
    aws_root = root / AWS_DIR
    if not aws_root.is_dir():
        return findings

    tf_files = sorted(p for p in aws_root.rglob("*.tf") if ".terraform" not in p.parts)

    for path in tf_files:
        rel = path.relative_to(root)
        text = strip_comments(path.read_text(encoding="utf-8"))

        # --- R1: an instance AMI that a third party can change ---------------------------
        volatile: set[str] = set()
        for match, body, _line in find_blocks(text, re.compile(r'data\s+"aws_ami"\s+"([A-Za-z0-9_-]+)"\s*')):
            if argument_value(body, "most_recent") == "true":
                volatile.add(match.group(1))

        for match, body, line in find_blocks(text, re.compile(r'resource\s+"aws_instance"\s+"([A-Za-z0-9_-]+)"\s*')):
            ami = argument_value(body, "ami")
            if ami is None:
                continue
            referenced = set(DATA_AWS_AMI_RE.findall(ami)) & volatile
            if referenced and not VAR_REF_RE.search(ami):
                findings.append(
                    f"{rel}:{line}: aws_instance.{match.group(1)} takes `ami` from the "
                    f"most_recent data source(s) {sorted(referenced)} with no pin variable. "
                    "That makes `ami` — a replace-forcing attribute — change whenever the "
                    "upstream publisher ships an image, so an unrelated `tofu apply` rebuilds "
                    "the instance (issue #3602). Use "
                    "`ami = var.<pin> != \"\" ? var.<pin> : data.aws_ami.<x>[0].id`."
                )

        # --- R2: an environment that selects fck-nat must pin the AMI --------------------
        if ENVS_DIR not in rel.parents:
            continue
        for match, body, line in find_blocks(text, re.compile(r'module\s+"([A-Za-z0-9_-]+)"\s*')):
            if argument_value(body, "egress_mode") != '"fck_nat"':
                continue
            pin = argument_value(body, "nat_ami_id")
            if pin is None or not AMI_ID_RE.match(pin):
                shown = "unset" if pin is None else pin
                findings.append(
                    f"{rel}:{line}: module \"{match.group(1)}\" runs egress through a fck-nat "
                    f"instance but nat_ami_id is {shown}. An environment with a live NAT must "
                    "pin a concrete ami-... id, or the module falls back to the newest "
                    "published AMI and a plain `tofu apply` destroys and recreates the single "
                    "NAT, dropping all private-subnet egress (issue #3602)."
                )
    return findings


def check(root: pathlib.Path, enforce: bool) -> int:
    findings = scan(root)
    level = "error" if enforce else "warning"
    for finding in findings:
        print(f"::{level}::{finding}")
    verdict = f"{len(findings)} finding(s)" if findings else "clean"
    print(f"check-nat-ami-pinned: {verdict}.")
    return 1 if findings and enforce else 0


def self_test() -> int:
    """Feed the checker the shapes it must flag AND the shapes it must not."""
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        module_dir = root / AWS_DIR / "modules" / "network"
        env_dir = root / ENVS_DIR / "sandbox-substrate"
        module_dir.mkdir(parents=True)
        env_dir.mkdir(parents=True)
        module_tf = module_dir / "main.tf"
        env_tf = env_dir / "main.tf"

        shipped_module = (
            'data "aws_ami" "fck_nat" {\n'
            "  count       = var.egress_mode == \"fck_nat\" ? 1 : 0\n"
            "  most_recent = true\n"
            "}\n\n"
            'resource "aws_instance" "fck_nat" {\n'
            "  count = var.egress_mode == \"fck_nat\" ? 1 : 0\n"
            '  ami   = var.nat_ami_id != "" ? var.nat_ami_id : data.aws_ami.fck_nat[0].id\n'
            "}\n"
        )
        shipped_env = (
            'module "network" {\n'
            '  source      = "../../modules/network"\n'
            '  egress_mode = "fck_nat"\n'
            '  nat_ami_id  = "ami-08c439a446e724124"\n'
            "}\n"
        )
        module_tf.write_text(shipped_module)
        env_tf.write_text(shipped_env)

        def verdict() -> int:
            return check(root, enforce=True)

        # (a) The shipped shape must pass — a guard that flags its own fix is worse than none.
        if verdict() != 0:
            failures.append("flagged the shipped shape (pinned env + conditional module ami)")

        # (b) R1: the pre-#3602 shape must be flagged.
        module_tf.write_text(shipped_module.replace(
            '  ami   = var.nat_ami_id != "" ? var.nat_ami_id : data.aws_ami.fck_nat[0].id',
            "  ami   = data.aws_ami.fck_nat[0].id",
        ))
        if verdict() == 0:
            failures.append("accepted `ami = data.aws_ami.fck_nat[0].id` off a most_recent source")

        # (c) An aws_ami lookup that is NOT most_recent is a deterministic lookup, not a
        #     landmine — R1 must not flag it, or the rule degenerates into "never use a data
        #     source" and gets suppressed wholesale.
        module_tf.write_text(
            'data "aws_ami" "fixed" {\n  owners = ["self"]\n}\n\n'
            'resource "aws_instance" "fck_nat" {\n  ami = data.aws_ami.fixed.id\n}\n'
        )
        if verdict() != 0:
            failures.append("flagged an aws_ami data source that is not most_recent")

        # (d) Code-about-code: the forbidden shape quoted inside comments must NOT be flagged.
        #     This checker's own subject matter is the violation, so the comment-stripping is
        #     load-bearing (the repo has paid for this collision several times).
        module_tf.write_text(
            "# The old, broken shape was:\n"
            "#   data \"aws_ami\" \"fck_nat\" { most_recent = true }\n"
            "#   ami = data.aws_ami.fck_nat[0].id\n"
            "/* also: most_recent = true\n   ami = data.aws_ami.fck_nat[0].id */\n"
            + shipped_module
        )
        if verdict() != 0:
            failures.append("flagged the forbidden shape where it appears only inside comments")

        module_tf.write_text(shipped_module)

        # (e) R2: an env selecting fck_nat with no pin at all.
        env_tf.write_text(shipped_env.replace('  nat_ami_id  = "ami-08c439a446e724124"\n', ""))
        if verdict() == 0:
            failures.append("accepted an fck_nat environment that sets no nat_ami_id")

        # (f) R2: an empty pin is the bootstrap escape hatch, and a live env must not keep it.
        env_tf.write_text(shipped_env.replace('"ami-08c439a446e724124"', '""'))
        if verdict() == 0:
            failures.append('accepted an fck_nat environment pinned to ""')

        # (g) R2 must not fire on the managed-NAT mode, which has no instance to replace.
        env_tf.write_text(
            'module "network" {\n  source      = "../../modules/network"\n'
            '  egress_mode = "managed_nat"\n}\n'
        )
        if verdict() != 0:
            failures.append("flagged a managed_nat environment, which has no NAT instance at all")

        # (h) Advisory mode must stay non-blocking even with a real finding present.
        env_tf.write_text(shipped_env.replace('  nat_ami_id  = "ami-08c439a446e724124"\n', ""))
        if check(root, enforce=False) != 0:
            failures.append("exited non-zero without --enforce")

    for message in failures:
        print(f"::error::self-test FAILED — the checker {message}.")
    print("check-nat-ami-pinned --self-test: " + ("clean." if not failures else "FAILED."))
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Guard: the NAT instance's AMI is pinned.")
    parser.add_argument("--root", default=".", help="repository root to scan")
    parser.add_argument("--enforce", action="store_true", help="fail (exit 1) instead of warning")
    parser.add_argument("--self-test", action="store_true", help="prove the checker's RED is reachable")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return check(pathlib.Path(args.root), enforce=args.enforce)


if __name__ == "__main__":
    sys.exit(main())
