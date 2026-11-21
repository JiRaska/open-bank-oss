#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ADR-0358: image pins live on the bot-owned `deploy/sandbox` branch, not in main.
#
# A pin is a directory `pins/<component>/` holding:
#   pin.json            what is pinned: manifest_sha (main commit the manifests are copied
#                       from), image_source_sha (main commit the image was built from),
#                       image, tag, digest, rollback flag
#   manifests/*         byte-for-byte copy of openbank-infra/gitops/components/<component>/
#                       at manifest_sha
#   kustomization.yaml  lists manifests/* as resources and overrides the image (tag + digest)
#
# The Argo CD Application on main points at that directory as a single kustomize source.
# Multi-source (manifests from main + overlay from deploy/sandbox) does not render: each
# source is built on its own, and a kustomize `images:` transformer only rewrites resources
# in its own kustomization. A remote base (`github.com/...?ref=<sha>`) does render, but has
# the repo-server clone the whole monorepo per render (a 27s fetch timeout measured locally)
# and runs outside Argo's repository credentials. A copy needs neither, and this checker
# proves the copy is exact.
#
# Subcommands:
#   render   write pins/<component>/ for a manifest sha + image (used by the deploy workflow)
#   check    verify one deploy-branch commit against its parent (workflow pre-commit, push
#            watch, and the `deploy-pin-branch-contract` gate)
#   --self-test   known-positive and known-negative cases, including every rejection rule
#
# Contract checked by `check` (ADR-0358 Enforcement):
#   1. the commit touches only pins/**
#   2. every changed pin is well-formed: digest present, kustomization agrees with pin.json
#   3. manifests/ equals the component directory on main at manifest_sha, file for file
#   4. manifest_sha and image_source_sha are ancestors of main
#   5. neither sha moves backwards against the parent pin (unless pin.json says rollback, which
#      still only permits an older IMAGE, never an older manifest set)
#   6. a component whose service is under rules.yaml deploy_holds (as of main) cannot change
#      its image. The service is the pinned image's repository name (openbank-aml-service),
#      never "openbank-" + the component directory (aml) - those differ for most services.
#
# Which image a component may pin (ADR-0358 slice 2): admin-ui is declared in PINNABLE; any
# other component is pinnable iff its directory on main at manifest_sha references exactly ONE
# distinct image repository in the fleet registry, and that repository is the one it may pin.
# The permission is derived from reviewed manifests, so it cannot name an image main does not
# already run there; a multi-image component (payments, accounts, ...) is not pinnable and
# stays on the deploy-PR path until a later slice handles it.

import argparse
import base64
import importlib.util
import json
import os
import posixpath
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

COMPONENTS_DIR = "openbank-infra/gitops/components"
PINS_DIR = "pins"
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
TAG = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$")
COMPONENT = re.compile(r"^[a-z0-9][a-z0-9-]*$")
PIN_KEYS = {"component", "manifest_sha", "image_source_sha", "image", "tag", "digest", "rollback"}
# Component -> the image it is allowed to pin, where declared rather than derived.
PINNABLE = {"admin-ui": "265175468565.dkr.ecr.eu-north-1.amazonaws.com/openbank-admin-ui"}
REGISTRY = PINNABLE["admin-ui"].rsplit("/", 1)[0]
# Image references are attributed by PARSING the manifests (YAML documents, JSON), never by
# matching `image:` lines: a flow-style mapping, a JSON file or an initContainer would hide a
# second image from a line regex. Fail closed: if the raw bytes mention the registry more often
# than the parse attributes to a container image, the component is not pinnable.
FLEET_REF = re.compile(r"^(" + re.escape(REGISTRY) + r"/openbank-[a-z0-9-]+)"
                       r"(?::[A-Za-z0-9_][A-Za-z0-9._-]{0,127})?(?:@sha256:[0-9a-f]{64})?$")
POD_TEMPLATE_KINDS = {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "Rollout"}
CONTAINER_KEYS = ("containers", "initContainers", "ephemeralContainers")


class PinError(Exception):
    pass


def git(root, *args, text=True):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=False)
    if result.returncode:
        raise PinError(f"git {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout.decode() if text else result.stdout


def tree_files(root, rev, path):
    """{name: bytes} for the regular files directly under `path` at `rev`; subdirectories fail."""
    files = {}
    for line in git(root, "ls-tree", f"{rev}:{path}").splitlines():
        meta, name = line.split("\t", 1)
        mode, kind, _ = meta.split()
        if kind != "blob" or mode not in ("100644", "100755"):
            raise PinError(f"{path}/{name} at {rev[:12]} is not a regular file ({kind} {mode})")
        files[name] = git(root, "show", f"{rev}:{path}/{name}", text=False)
    if not files:
        raise PinError(f"{path} is empty at {rev[:12]}")
    return files


def kustomization(names, image, tag, digest):
    doc = {
        "apiVersion": "kustomize.config.k8s.io/v1beta1",
        "kind": "Kustomization",
        "resources": [f"manifests/{n}" for n in sorted(names)],
        "images": [{"name": image, "newTag": tag, "digest": digest}],
    }
    header = "# Generated by .github/scripts/check-deploy-pin-branch.py render (ADR-0358). Do not edit.\n"
    return header + yaml.safe_dump(doc, sort_keys=False)


# Argo CD's plain-directory source (no kustomization, no `directory:` options) renders exactly
# the top-level *.yaml/*.yml/*.json files and ignores everything else (.rego, .sh, sub-
# directories). A derived pin copies exactly that set, so it renders what main renders today.
ARGO_MANIFEST = re.compile(r"^[^/]+\.(ya?ml|json)$")
KUSTOMIZATION = {"kustomization.yaml", "kustomization.yml", "Kustomization"}


def directory_manifests(root, rev, path):
    """{name: bytes} of the files Argo's directory source renders from `path` at `rev`."""
    files = {}
    try:
        listing = git(root, "ls-tree", f"{rev}:{path}").splitlines()
    except PinError as error:
        raise PinError(f"{path} does not exist at {rev[:12]}") from error
    for line in listing:
        meta, name = line.split("\t", 1)
        mode, kind, _ = meta.split()
        if name in KUSTOMIZATION:
            raise PinError(f"{path} is a kustomization at {rev[:12]}; only a plain-directory "
                           f"component is pinnable by derivation")
        if kind != "blob" or not ARGO_MANIFEST.fullmatch(name):
            continue
        if mode not in ("100644", "100755"):
            raise PinError(f"{path}/{name} at {rev[:12]} is not a regular file ({kind} {mode})")
        files[name] = git(root, "show", f"{rev}:{path}/{name}", text=False)
    if not files:
        raise PinError(f"{path} has no manifests at {rev[:12]}")
    return files


SYMLINK_MODE = "120000"
JSONNET = re.compile(r"\.(jsonnet|libsonnet)$")


def tree_modes(root, rev, paths):
    """{path: mode} for exactly the given repo paths at `rev` (one ls-tree call; missing paths
    are simply absent). Paths are passed literally, never resolved through the filesystem."""
    paths = sorted({p for p in paths if p and p != "."})
    if not paths:
        return {}
    out = {}
    for line in git(root, "ls-tree", "-z", "--full-tree", rev, "--", *paths).split("\0"):
        if line:
            meta, _, name = line.partition("\t")
            if name in paths:  # ls-tree also lists siblings of intermediate directories
                out[name] = meta.split()[0]
    return out


def prefixes(path):
    parts = [p for p in path.split("/") if p]
    return ["/".join(parts[:i]) for i in range(1, len(parts) + 1)]


def assert_component_tree(root, rev, component):
    """Fail closed when the component directory, any directory above it, or any entry anywhere
    beneath it is a symlink (Argo's repo-server follows links git stores as blobs, so the bytes
    this checker copies would not be the bytes Argo renders), or when it carries jsonnet."""
    path = f"{COMPONENTS_DIR}/{component}"
    for name, mode in tree_modes(root, rev, prefixes(path)).items():
        if mode == SYMLINK_MODE:
            raise PinError(f"{component}: {name} is a symlink at {rev[:12]}; fail closed")
    try:
        listing = git(root, "ls-tree", "-r", "-t", "-z", f"{rev}:{path}").split("\0")
    except PinError as error:
        raise PinError(f"{path} does not exist at {rev[:12]}") from error
    for line in listing:
        if not line:
            continue
        meta, _, name = line.partition("\t")
        if meta.split()[0] == SYMLINK_MODE:
            raise PinError(f"{component}: {path}/{name} is a symlink at {rev[:12]}; fail closed")
        if JSONNET.search(name):
            raise PinError(f"{component}: {path}/{name} is jsonnet at {rev[:12]}; Argo may evaluate "
                           f"it, a derived pin cannot reproduce that; fail closed")


def component_files(root, rev, component):
    """The manifest copy for a pin: admin-ui (declared) copies its whole directory as slice 1
    did; a derived component copies what Argo renders from its directory."""
    path = f"{COMPONENTS_DIR}/{component}"
    if not COMPONENT.fullmatch(component):
        raise PinError(f"{component!r} is not a valid component name")
    assert_component_tree(root, rev, component)
    if component in PINNABLE:
        return tree_files(root, rev, path)
    return directory_manifests(root, rev, path)


# Manifests are loaded with a loader that knows only the YAML core schema. Anything else
# (!!binary, !!timestamp, !!set, !!python/*, custom tags, `<<` merge keys, duplicate keys) is a
# place where what this checker parses and what kustomize/Argo parse can disagree, so it is a
# parse failure and the component is not pinnable.
CORE_TAGS = {f"tag:yaml.org,2002:{t}": c for t, c in (
    ("str", yaml.SafeLoader.construct_yaml_str), ("int", yaml.SafeLoader.construct_yaml_int),
    ("float", yaml.SafeLoader.construct_yaml_float), ("bool", yaml.SafeLoader.construct_yaml_bool),
    ("null", yaml.SafeLoader.construct_yaml_null), ("map", yaml.SafeLoader.construct_yaml_map),
    ("seq", yaml.SafeLoader.construct_yaml_seq))}


class StrictLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        if isinstance(node, yaml.MappingNode):
            seen = set()
            for key_node, _ in node.value:
                key = self.construct_object(key_node, deep=True)
                try:
                    duplicate = key in seen
                except TypeError as error:
                    raise yaml.constructor.ConstructorError(
                        None, None, f"unhashable mapping key {key!r}", key_node.start_mark) from error
                if duplicate:
                    raise yaml.constructor.ConstructorError(
                        None, None, f"duplicate mapping key {key!r}", key_node.start_mark)
                seen.add(key)
        return super().construct_mapping(node, deep=deep)


def _reject_tag(loader, node):
    raise yaml.constructor.ConstructorError(None, None, f"tag {node.tag!r} is outside the core "
                                            f"schema", node.start_mark)


StrictLoader.yaml_constructors = dict(CORE_TAGS)
StrictLoader.yaml_constructors[None] = _reject_tag
StrictLoader.yaml_multi_constructors = {}
StrictLoader.yaml_implicit_resolvers = {
    ch: [(tag, rx) for tag, rx in resolvers if tag in CORE_TAGS]
    for ch, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()}


def _no_duplicate_pairs(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise ValueError(f"duplicate JSON key in {keys}")
    return dict(pairs)


def _scalars(obj, path=(), stack=()):
    """(position, text) for EVERY string scalar in a parsed document: mapping keys and values,
    sequence items, at any depth. Positions, not identities: an alias that repeats a scalar
    yields it once per place it appears. bytes are decoded; a self-referencing alias raises."""
    if isinstance(obj, (dict, list)):
        if id(obj) in stack or len(stack) > 200:
            raise ValueError("recursive or too deeply nested document")
        stack = stack + (id(obj),)
        items = obj.items() if isinstance(obj, dict) else enumerate(obj)
        for k, v in items:
            if isinstance(obj, dict):
                yield from _scalars(k, path + (("<key>", repr(k)),), stack)
            yield from _scalars(v, path + (k,), stack)
    elif isinstance(obj, (bytes, bytearray)):
        yield path, bytes(obj).decode("latin-1")
    elif isinstance(obj, str):
        yield path, obj


def _pod_spec(doc):
    """(pod spec, its position in doc) for a pod-bearing kind, else (None, None)."""
    kind = doc.get("kind")
    spec = doc.get("spec")
    if not isinstance(spec, dict):
        return None, None
    if kind == "Pod":
        return spec, ("spec",)
    path = ("spec",)
    if kind == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec") or {})
        path = ("spec", "jobTemplate", "spec")
    elif kind not in POD_TEMPLATE_KINDS:
        return None, None
    template = spec.get("template") if isinstance(spec, dict) else None
    if not isinstance(template, dict) or not isinstance(template.get("spec"), dict):
        return None, None
    return template["spec"], path + ("template", "spec")


def _doc_images(doc, path=()):
    """(position, image) for every image string a parsed manifest document attributes to a
    container (or a kustomization `images:` entry). `kind: List` is walked."""
    if isinstance(doc, list):
        return [x for i, d in enumerate(doc) for x in _doc_images(d, path + (i,))]
    if not isinstance(doc, dict):
        return []
    if doc.get("kind") == "List":
        items = doc.get("items") or []
        return [x for i, d in enumerate(items if isinstance(items, list) else [])
                for x in _doc_images(d, path + ("items", i))]
    if doc.get("kind") == "Kustomization":
        images = doc.get("images") or []
        return [(path + ("images", i, f), e[f]) for i, e in enumerate(images if isinstance(images, list) else [])
                if isinstance(e, dict) for f in ("name", "newName") if isinstance(e.get(f), str)]
    pod, pod_path = _pod_spec(doc)
    if not isinstance(pod, dict):
        return []
    out = []
    for key in CONTAINER_KEYS:
        containers = pod.get(key) or []
        for i, c in enumerate(containers if isinstance(containers, list) else []):
            if isinstance(c, dict) and isinstance(c.get("image"), str):
                out.append((path + pod_path + (key, i, "image"), c["image"]))
    return out


def _mentions_registry(text):
    return REGISTRY in text.lower()


def parse_images(name, data):
    """Every container image the file `name` declares, or None when it cannot be parsed or
    when ANY string scalar anywhere in it mentions the fleet registry at a position that is
    not an attributed container/initContainer/ephemeralContainer image or kustomize
    `images:` entry (annotation, env value, ConfigMap data, an alias of an image, a key...)."""
    try:
        text = data.decode("utf-8")
        if name.endswith(".json"):
            docs = [json.loads(text, object_pairs_hook=_no_duplicate_pairs)]
        else:
            docs = list(yaml.load_all(text, Loader=StrictLoader))
        images = []
        for d in docs:
            attributed = dict(_doc_images(d))
            for position, scalar in _scalars(d):
                if _mentions_registry(scalar) and attributed.get(position) != scalar:
                    return None
            images.extend(attributed.values())
    except (UnicodeDecodeError, ValueError, RecursionError, yaml.YAMLError):
        return None
    return images


def derived_image(files):
    """The single fleet-registry image repository a component's manifests reference, or None
    when they reference none or more than one, or when a registry mention is not attributable
    to a parsed container image (not pinnable by derivation; fail closed)."""
    repos = set()
    for name, data in files.items():
        images = parse_images(name, data)
        if images is None:
            return None
        fleet = [i for i in images if _mentions_registry(i)]
        # Second, independent guard: the raw byte count must agree with the parsed one.
        if data.lower().count(REGISTRY.encode()) != len(fleet):
            return None
        for image in fleet:
            match = FLEET_REF.fullmatch(image)
            if not match:
                return None
            repos.add(match.group(1))
    return next(iter(repos)) if len(repos) == 1 else None


def pinnable_image(component, files):
    """The image `component` may pin, given its manifest files at manifest_sha."""
    if not COMPONENT.fullmatch(component):
        raise PinError(f"{component!r} is not a valid component name")
    if component in PINNABLE:
        return PINNABLE[component]
    image = derived_image(files)
    if image is None:
        raise PinError(f"{component} is not a pinnable component: its manifests on main do not "
                       f"reference exactly one {REGISTRY}/openbank-* image (declared: {sorted(PINNABLE)})")
    return image


def service_of(image):
    """deploy_holds key for a pinned image: its repository name."""
    return image.rsplit("/", 1)[1]


def render(root, component, manifest_sha, image_source_sha, tag, digest, rollback=False):
    """{relative path under pins/<component>/: bytes} for one pin."""
    if not COMPONENT.fullmatch(component):
        raise PinError(f"{component!r} is not a valid component name")
    try:
        files = component_files(root, manifest_sha, component)
    except PinError as error:
        raise PinError(f"{component} is not a pinnable component: {error}") from error
    image = pinnable_image(component, files)
    pin = {"component": component, "manifest_sha": manifest_sha, "image_source_sha": image_source_sha,
           "image": image, "tag": tag, "digest": digest, "rollback": bool(rollback)}
    validate_pin(pin, component, image)
    out = {f"manifests/{n}": data for n, data in files.items()}
    out["kustomization.yaml"] = kustomization(files, image, tag, digest).encode()
    out["pin.json"] = (json.dumps(pin, indent=2, sort_keys=True) + "\n").encode()
    return out


def validate_pin(pin, component, image=None):
    """Shape check. `image` is the image the component may pin at pin["manifest_sha"]; render()
    passes it, and a full check re-derives it there (an image naming no manifest is rejected
    by the byte comparison against render())."""
    if not isinstance(pin, dict) or set(pin) != PIN_KEYS:
        raise PinError(f"{component}: pin.json must have exactly {sorted(PIN_KEYS)}")
    if pin["component"] != component:
        raise PinError(f"{component}: pin.json names component {pin['component']!r}")
    for key in ("manifest_sha", "image_source_sha"):
        if not isinstance(pin[key], str) or not SHA.fullmatch(pin[key]):
            raise PinError(f"{component}: {key} must be a full 40-hex commit sha")
    if not isinstance(pin["image"], str) or not pin["image"].startswith(REGISTRY + "/openbank-"):
        raise PinError(f"{component}: image {pin['image']!r} is not a fleet-registry image")
    if image is not None and pin["image"] != image:
        raise PinError(f"{component}: image {pin['image']!r} is not the image this component may pin")
    if not isinstance(pin["tag"], str) or not TAG.fullmatch(pin["tag"]):
        raise PinError(f"{component}: invalid tag {pin['tag']!r}")
    if not isinstance(pin["digest"], str) or not DIGEST.fullmatch(pin["digest"]):
        raise PinError(f"{component}: digest missing or not sha256:<64 hex> ({pin['digest']!r})")
    if not isinstance(pin["rollback"], bool):
        raise PinError(f"{component}: rollback must be a boolean")


def read_pin(root, rev, component):
    """pin.json of `component` at deploy-branch `rev`, or None when the pin does not exist."""
    try:
        raw = git(root, "show", f"{rev}:{PINS_DIR}/{component}/pin.json")
    except PinError:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as error:
        raise PinError(f"{component}: pin.json at {rev[:12]} is not JSON: {error}") from error


def _holds_module():
    spec = importlib.util.spec_from_file_location(
        "deploy_holds", Path(__file__).resolve().parent / "check-deploy-holds.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_holds(root, main_rev):
    """{service: hold entry} under deploy_holds as of main, through the existing hold parser."""
    module = _holds_module()
    text = git(root, "show", f"{main_rev}:openbank-libs/governance/rules.yaml")
    try:
        return module.holds(text)
    except module.InvalidHold as error:
        raise PinError(str(error)) from error


def check_hold(component, pin, holds, image_changed):
    """A held service keeps its image; where the hold records approved_image, the pin must name
    exactly that image (same semantics as check-deploy-holds.py's pin_matches)."""
    service = service_of(pin["image"])
    if service not in holds:
        return
    if image_changed:
        raise PinError(f"{component}: {service} is under a deploy hold in rules.yaml; its image pin "
                       f"cannot change")
    approved = holds[service].get("approved_image")
    ref = f"{pin['image']}:{pin['tag']}@{pin['digest']}"
    if approved and not _holds_module().pin_matches(ref, approved):
        raise PinError(f"{component}: {service} is under a deploy hold and its pin {pin['tag']}@"
                       f"{pin['digest']} differs from approved_image {approved}")


# Argo CD Application sources that point at a component. A derived pin copies exactly the
# top-level manifests, which is what a PLAIN directory source renders; recurse/include/exclude
# (or jsonnet/plugin/kustomize/helm options) would make main render a different set, so the
# contract asserts their absence rather than assuming it.
GITOPS_DIR = "openbank-infra/gitops"
THIS_REPO = "github.com/jiraska/open-bank-oss"
APP_KINDS = ("Application", "ApplicationSet")
SOURCE_OPTIONS = ("directory", "helm", "kustomize", "plugin", "chart")


def normalise_repo(url):
    """Repository identity of an Argo repoURL: scheme, user, `.git`, case and slashes dropped."""
    u = str(url or "").strip().lower()
    u = re.sub(r"^[a-z+]+://", "", u)
    u = re.sub(r"^[^@/]+@", "", u)
    u = u.replace(":", "/", 1) if re.match(r"^[^/]+:[^0-9]", u) else u
    u = u.rstrip("/")
    return u[:-4] if u.endswith(".git") else u


def normalise_path(path):
    """A source path the way Argo resolves it against the repo root. Backslashes are NOT
    rewritten: a path holding one fails closed in assert_argo_sources_safe instead."""
    p = posixpath.normpath(str(path if path is not None else "") or ".")
    p = p.lstrip("/")
    while p.startswith("./"):
        p = p[2:]
    return p.rstrip("/") or "."


def _source_dicts(obj):
    """Every dict under a `source:` key or in a `sources:` list, at any depth, with a flag for
    being one of several (multi-source). ApplicationSet templates and generator-level
    templates are all found this way."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "source" and isinstance(v, dict):
                yield v, False
            elif k == "sources" and isinstance(v, list):
                yield from ((s, True) for s in v if isinstance(s, dict))
            yield from _source_dicts(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _source_dicts(v)


def _may_hold_application(data):
    # Cheap prefilter over ~1000 files. A document whose kind is Application/ApplicationSet
    # contains the literal word unless it is spelled through an escape (backslash), a tag
    # (`!`), or a non-UTF-8 encoding; all of those are parsed rather than skipped.
    return (b"Application" in data or b"\\" in data or b"!" in data
            or data[:2] in (b"\xff\xfe", b"\xfe\xff") or b"\x00" in data)


def application_sources(root, rev):
    """[(file, source dict, multi_source)] for every Argo Application and ApplicationSet source
    (spec.source, spec.sources[], templates at any depth) anywhere under GITOPS_DIR at `rev`."""
    return [(name, src, multi) for name, doc in application_docs(root, rev)
            for src, multi in _source_dicts(doc.get("spec") or {})]


def application_docs(root, rev):
    """[(file, doc)] for every Argo Application/ApplicationSet document under GITOPS_DIR."""
    out = []
    listing = git(root, "ls-tree", "-r", rev, f"{GITOPS_DIR}/")
    blobs = []
    for line in listing.splitlines():
        meta, _, name = line.partition("\t")
        parts = meta.split()
        if len(parts) == 3 and parts[1] == "blob" and re.search(r"\.(ya?ml|json)$", name):
            blobs.append((name, parts[2]))
    if not blobs:
        return out
    batch = subprocess.run(["git", "-C", str(root), "cat-file", "--batch"], check=True,
                           input="".join(f"{sha}\n" for _, sha in blobs).encode(),
                           capture_output=True).stdout
    offset = 0
    for name, _sha in blobs:
        header_end = batch.index(b"\n", offset)
        size = int(batch[offset:header_end].split()[2])
        data = batch[header_end + 1:header_end + 1 + size]
        offset = header_end + 1 + size + 1
        if not _may_hold_application(data):
            continue
        try:
            text = data.decode("utf-8")
            docs = ([json.loads(text)] if name.endswith(".json")
                    else list(yaml.load_all(text, Loader=getattr(yaml, "CSafeLoader", yaml.SafeLoader))))
        except (UnicodeDecodeError, ValueError, yaml.YAMLError) as error:
            raise PinError(f"{name} at {rev[:12]} does not parse: {error}") from error
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in APP_KINDS:
                continue
            out.append((name, doc))
    return out


def assert_plain_directory_source(root, rev, component):
    """Raise unless every Application/ApplicationSet source rendering components/<component>
    (or pins/<component>) does so as this repo's plain, non-recursive, single source, and at
    least one does. Paths are normalised as Argo resolves them; a source from any other
    repoURL that resolves to the component path fails closed."""
    targets = {f"{COMPONENTS_DIR}/{component}", f"{PINS_DIR}/{component}"}
    found = 0
    for name, src, multi in application_sources(root, rev):
        path = normalise_path(src.get("path"))
        options = [k for k in SOURCE_OPTIONS if k in src]
        directory = src.get("directory")
        plain = options == [] or (options == ["directory"] and isinstance(directory, dict) and not {
            k: v for k, v in directory.items() if v not in (None, False, "", {}, [])})
        if path not in targets:
            # A recursing parent directory renders the component too.
            recurse = isinstance(directory, dict) and directory.get("recurse") not in (None, False, "", "false")
            if recurse and any(t.startswith("" if path == "." else path + "/") for t in targets):
                raise PinError(f"{component}: {name} renders {src.get('path')!r} recursively, which "
                               f"includes the component; a derived pin cannot reproduce that")
            continue
        if normalise_repo(src.get("repoURL")) != THIS_REPO:
            raise PinError(f"{component}: {name} has a source at {src.get('path')!r} from repoURL "
                           f"{src.get('repoURL')!r}, not this repository; fail closed")
        found += 1
        if multi:
            raise PinError(f"{component}: {name} renders {src.get('path')!r} as one of several "
                           f"sources; a derived pin renders as a single source")
        if not plain:
            raise PinError(f"{component}: {name} renders {src.get('path')!r} with source options "
                           f"{options} ({directory!r}); a derived pin copies only the plain top-level "
                           f"directory, so it would not render what main renders")
    if not found:
        raise PinError(f"{component}: no Argo Application under {GITOPS_DIR} renders "
                       f"{COMPONENTS_DIR}/{component} at {rev[:12]}")


# ApplicationSet generators whose output this checker does not evaluate. Argo is never
# emulated: if one of them could reach a target path, the component is not pinnable.
OPAQUE_GENERATORS = ("git", "matrix", "merge", "plugin", "scmProvider", "clusterDecisionResource")
GLOB = re.compile(r"[*?\[{]")


def static_prefix(path):
    """The directory part of `path` before its first template or glob character, normalised;
    None when nothing static precedes it (the path could be anywhere)."""
    raw = str(path or "")
    cut = min([i for i in (raw.find("{{"), *(m.start() for m in [GLOB.search(raw)] if m)) if i >= 0],
              default=None)
    if cut is None:
        return normalise_path(raw)
    head = raw[:cut]
    head = head[:head.rfind("/") + 1] if "/" in head else ""
    p = normalise_path(head)
    return None if p == "." else p


def _covers(prefix, target):
    return prefix is None or prefix == "." or target == prefix or target.startswith(prefix + "/")


def _generators(obj):
    """Every generator mapping under spec.generators, including those nested in matrix/merge."""
    if isinstance(obj, list):
        for g in obj:
            yield from _generators(g)
    elif isinstance(obj, dict):
        for kind, body in obj.items():
            if kind in ("matrix", "merge") and isinstance(body, dict):
                yield kind, body
                yield from _generators(body.get("generators") or [])
            elif kind != "selector":
                yield kind, body


def assert_argo_sources_safe(root, rev, component):
    """Fail closed on every Argo construct whose rendered source path this checker would have
    to emulate: templated path/repoURL, templatePatch, opaque generators over the component, a
    backslash or a symlink in a source path, or jsonnet options on a source of the component."""
    targets = {f"{COMPONENTS_DIR}/{component}", f"{PINS_DIR}/{component}"}
    docs = application_docs(root, rev)
    raw_paths = set()
    for name, doc in docs:
        spec = doc.get("spec") or {}
        appset = doc.get("kind") == "ApplicationSet"
        if appset and "templatePatch" in spec:
            raise PinError(f"{component}: {name} is an ApplicationSet with templatePatch, which can "
                           f"rewrite any source path; fail closed")
        template_prefixes = []
        for src, _ in _source_dicts(spec):
            raw = str(src.get("path") if src.get("path") is not None else "")
            repo = str(src.get("repoURL") or "")
            if "\\" in raw:
                raise PinError(f"{component}: {name} has a source path with a backslash ({raw!r}); "
                               f"fail closed")
            templated = "{{" in raw or "{{" in repo
            prefix = static_prefix(raw)
            if templated:
                if prefix is None:
                    raise PinError(f"{component}: {name} has a templated source path {raw!r} with no "
                                   f"static prefix; it may render anything under {GITOPS_DIR}; fail closed")
                if any(_covers(prefix, t) for t in targets):
                    raise PinError(f"{component}: {name} has a templated source ({raw!r}, repoURL "
                                   f"{repo!r}) whose static prefix {prefix!r} covers the component; "
                                   f"fail closed")
            else:
                raw_paths.add(raw)
            template_prefixes.append(prefix)
            if normalise_path(raw) in targets:
                directory = src.get("directory")
                if isinstance(directory, dict) and "jsonnet" in directory:
                    raise PinError(f"{component}: {name} renders {raw!r} with jsonnet options; fail closed")
        if not appset:
            continue
        for kind, body in _generators(spec.get("generators") or []):
            if kind not in OPAQUE_GENERATORS:
                continue
            gen_prefixes = list(template_prefixes)
            if kind == "git" and isinstance(body, dict):
                for entry in (body.get("directories") or []) + (body.get("files") or []):
                    p = entry.get("path") if isinstance(entry, dict) else None
                    if p is not None and "\\" in str(p):
                        raise PinError(f"{component}: {name} has a git generator path with a "
                                       f"backslash ({p!r}); fail closed")
                    gen_prefixes.append(static_prefix(p))
            for prefix in gen_prefixes:
                if any(_covers(prefix, t) for t in targets):
                    raise PinError(f"{component}: {name} has a {kind} generator whose static path "
                                   f"{prefix!r} covers the component; a derived pin cannot emulate "
                                   f"what it generates; fail closed")
    # Resolve every static source path against the git tree, literally: any symlinked
    # component of it can make Argo render the component from a path this checker never matched.
    wanted = set()
    for raw in raw_paths:
        segs = []
        for seg in raw.split("/"):
            if seg == "..":
                break
            if seg not in ("", "."):
                segs.append(seg)
        wanted.update(prefixes("/".join(segs)))
        norm = normalise_path(raw)
        if norm != ".":
            wanted.update(prefixes(norm))
    for name, mode in tree_modes(root, rev, wanted).items():
        if mode == SYMLINK_MODE:
            raise PinError(f"{component}: Argo source path component {name} is a symlink at "
                           f"{rev[:12]}; fail closed")


def tip_contract(root, component, pin, old, main_rev):
    """What a pin must agree with at the main TIP, not at its own (writer-chosen) manifest_sha:
    the image is the one the component runs on main now, and a first pin copies manifests no
    older than main's latest change to the component."""
    files = component_files(root, main_rev, component)
    tip_image = pinnable_image(component, files)
    if pin["image"] != tip_image:
        raise PinError(f"{component}: image {pin['image']!r} is not the image the component runs on "
                       f"main now ({tip_image!r})")
    assert_argo_sources_safe(root, main_rev, component)
    if component not in PINNABLE:
        assert_plain_directory_source(root, main_rev, component)
    if old is None:
        last = git(root, "rev-list", "-1", main_rev, "--", f"{COMPONENTS_DIR}/{component}/").strip()
        if not last or not is_ancestor(root, last, pin["manifest_sha"]):
            raise PinError(f"{component}: first pin copies manifests at {pin['manifest_sha'][:12]}, "
                           f"older than main's latest change to the component ({last[:12]}); a "
                           f"first pin must start from current manifests")


def is_ancestor(root, older, newer):
    result = subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", older, newer],
                            capture_output=True, check=False)
    if result.returncode not in (0, 1):
        raise PinError(f"cannot decide ancestry {older[:12]} -> {newer[:12]}: "
                       f"{result.stderr.decode(errors='replace').strip()} (history too shallow?)")
    return result.returncode == 0


def check_commit(root, commit, parent, main_rev):
    """Raise PinError unless deploy-branch `commit` (child of `parent`, or None for the root
    commit) satisfies the ADR-0358 contract. Returns the components it changed."""
    if parent:
        changed = git(root, "diff", "--name-only", "--no-renames", parent, commit).splitlines()
    else:
        changed = git(root, "ls-tree", "-r", "--name-only", commit).splitlines()
        # The bootstrap root commit carries only a README; everything after it is pins.
        changed = [p for p in changed if p != "README.md"]
    if parent and not changed:
        raise PinError(f"{commit[:12]} changes nothing; the bot never writes an empty pin commit")
    outside = [p for p in changed if not p.startswith(f"{PINS_DIR}/")]
    if outside:
        raise PinError(f"commit touches paths outside {PINS_DIR}/: {', '.join(outside)}")
    components = sorted({p.split("/")[1] for p in changed})
    holds = load_holds(root, main_rev)
    for component in components:
        if not COMPONENT.fullmatch(component):
            raise PinError(f"{PINS_DIR}/{component} is not a pinnable component")
        pin = read_pin(root, commit, component)
        if pin is None:
            raise PinError(f"{component}: pin removed or pin.json missing — removing a pin un-deploys "
                           f"the component and is not a bot action")
        validate_pin(pin, component)
        expected = render(root, component, pin["manifest_sha"], pin["image_source_sha"],
                          pin["tag"], pin["digest"], pin["rollback"])
        actual = {}
        for line in git(root, "ls-tree", "-r", commit, f"{PINS_DIR}/{component}/").splitlines():
            path = line.split("\t", 1)[1]
            actual[path[len(f"{PINS_DIR}/{component}/"):]] = git(root, "show", f"{commit}:{path}", text=False)
        if set(actual) != set(expected):
            raise PinError(f"{component}: file set differs from main@{pin['manifest_sha'][:12]}: "
                           f"extra {sorted(set(actual) - set(expected))}, "
                           f"missing {sorted(set(expected) - set(actual))}")
        for path in sorted(expected):
            if actual[path] != expected[path]:
                raise PinError(f"{component}: {path} differs from what main@{pin['manifest_sha'][:12]} "
                               f"and pin.json produce (hand edit, or manifests not copied verbatim)")
        for key in ("manifest_sha", "image_source_sha"):
            if not is_ancestor(root, pin[key], main_rev):
                raise PinError(f"{component}: {key} {pin[key][:12]} is not an ancestor of main")
        old = read_pin(root, parent, component) if parent else None
        if old is not None:
            validate_pin(old, component)
            if not is_ancestor(root, old["manifest_sha"], pin["manifest_sha"]):
                raise PinError(f"{component}: manifest_sha moves backwards "
                               f"({old['manifest_sha'][:12]} is not an ancestor of {pin['manifest_sha'][:12]})")
            if pin["rollback"] and not was_pinned_before(root, parent, component, pin):
                raise PinError(f"{component}: rollback target {pin['tag']} ({pin['digest']}) was never pinned "
                               f"on this branch; a rollback may only restore an earlier pin")
            if not pin["rollback"] and not is_ancestor(root, old["image_source_sha"], pin["image_source_sha"]):
                raise PinError(f"{component}: image_source_sha moves backwards "
                               f"({old['image_source_sha'][:12]} -> {pin['image_source_sha'][:12]}); "
                               f"an older build may not overwrite a newer pin")
        tip_contract(root, component, pin, old, main_rev)
        image_changed = old is None or (old["tag"], old["digest"]) != (pin["tag"], pin["digest"])
        check_hold(component, pin, holds, image_changed)
    return components


def was_pinned_before(root, rev, component, pin):
    """True when some earlier deploy-branch commit up to `rev` pinned exactly this image."""
    want = (pin["image_source_sha"], pin["tag"], pin["digest"])
    path = f"{PINS_DIR}/{component}/pin.json"
    for old_rev in git(root, "rev-list", "--first-parent", rev, "--", path).split():
        old = read_pin(root, old_rev, component)
        if old and (old.get("image_source_sha"), old.get("tag"), old.get("digest")) == want:
            return True
    return False


def check_state(root, rev, main_rev):
    """Verify every pin present at `rev`, whether or not `rev` touched it: a delta check of the
    tip alone would pass a branch whose earlier, unverified commit planted a bad pin."""
    names = git(root, "ls-tree", "--name-only", f"{rev}:{PINS_DIR}").split() if subprocess.run(
        ["git", "-C", str(root), "cat-file", "-e", f"{rev}:{PINS_DIR}"], capture_output=True).returncode == 0 else []
    top = [p for p in git(root, "ls-tree", "--name-only", rev).split() if p not in (PINS_DIR, "README.md")]
    if top:
        raise PinError(f"{rev[:12]} carries paths outside {PINS_DIR}/: {', '.join(top)}")
    holds = load_holds(root, main_rev)
    for component in names:
        if not COMPONENT.fullmatch(component):
            raise PinError(f"{PINS_DIR}/{component} is not a pinnable component")
        pin = read_pin(root, rev, component)
        if pin is None:
            raise PinError(f"{component}: pin.json missing at {rev[:12]}")
        validate_pin(pin, component)
        expected = render(root, component, pin["manifest_sha"], pin["image_source_sha"],
                          pin["tag"], pin["digest"], pin["rollback"])
        prefix = f"{PINS_DIR}/{component}/"
        actual = {line.split("\t", 1)[1][len(prefix):]: git(root, "show", f"{rev}:{line.split(chr(9), 1)[1]}", text=False)
                  for line in git(root, "ls-tree", "-r", rev, prefix).splitlines()}
        if actual != expected:
            raise PinError(f"{component}: tip state differs from what main@{pin['manifest_sha'][:12]} and "
                           f"pin.json produce")
        for key in ("manifest_sha", "image_source_sha"):
            if not is_ancestor(root, pin[key], main_rev):
                raise PinError(f"{component}: {key} {pin[key][:12]} is not an ancestor of main")
        # The image must still be the one main runs there, and a held service must match its
        # approved_image. Manifest staleness is not checked here: a push to main legitimately
        # leaves the pin behind until the re-pin lands.
        tip_files = component_files(root, main_rev, component)
        if pin["image"] != pinnable_image(component, tip_files):
            raise PinError(f"{component}: image {pin['image']!r} is not the image the component "
                           f"runs on main now")
        assert_argo_sources_safe(root, main_rev, component)
        if component not in PINNABLE:
            assert_plain_directory_source(root, main_rev, component)
        check_hold(component, pin, holds, image_changed=False)
        print(f"OK state {component} {pin['tag']} manifests@{pin['manifest_sha'][:12]}")
    return len(names)


def check_range(root, base, head, main_rev):
    """Check every first-parent commit in base..head (base may be None for the whole branch)."""
    revs = git(root, "rev-list", "--reverse", "--first-parent", f"{base}..{head}" if base else head).split()
    for rev in revs:
        parents = git(root, "rev-list", "--parents", "-n", "1", rev).split()[1:]
        if len(parents) > 1:
            raise PinError(f"{rev[:12]} is a merge commit; deploy/sandbox is linear")
        components = check_commit(root, rev, parents[0] if parents else None, main_rev)
        print(f"OK {rev[:12]} pins {', '.join(components) or '(nothing)'}")
    return len(revs)


# ---------------------------------------------------------------- self-test

def self_test():
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        def run(*args):
            git(root, *args)

        run("init", "-q", "-b", "main")
        run("config", "user.email", "t@example.invalid")
        run("config", "user.name", "t")
        run("config", "commit.gpgsign", "false")
        real = Path(__file__).resolve().parents[2]
        rules = (real / "openbank-libs/governance/rules.yaml").read_text()
        comp = root / COMPONENTS_DIR / "admin-ui"
        comp.mkdir(parents=True)
        (root / "openbank-libs/governance").mkdir(parents=True)
        (root / "openbank-libs/governance/rules.yaml").write_text(rules)
        (comp / "admin-ui.yaml").write_text(f"kind: Deployment\nimage: {PINNABLE['admin-ui']}:old\n")
        (comp / "namespace.yaml").write_text("kind: Namespace\n")
        run("add", "-A")
        run("commit", "-qm", "m1")
        m1 = git(root, "rev-parse", "HEAD").strip()
        (comp / "namespace.yaml").write_text("kind: Namespace\nmetadata: {name: admin-ui}\n")
        run("commit", "-qam", "m2")
        m2 = git(root, "rev-parse", "HEAD").strip()
        # A commit that never reached main.
        run("checkout", "-q", "-b", "side", m1)
        (comp / "admin-ui.yaml").write_text("kind: Deployment\nimage: evil\n")
        run("commit", "-qam", "side")
        side = git(root, "rev-parse", "HEAD").strip()
        run("checkout", "-q", "main")

        d1 = "sha256:" + "1" * 64
        d2 = "sha256:" + "2" * 64

        def deploy_commit(parent, files, message="pin"):
            """Commit {path: bytes|None} onto deploy/sandbox parent (None = orphan root)."""
            run("checkout", "-q", "--orphan" if parent is None else "--detach",
                *(["deploy-tmp"] if parent is None else [parent]))
            if parent is None:
                run("rm", "-rqf", "--cached", ".")
                for p in root.iterdir():
                    if p.name != ".git":
                        subprocess.run(["rm", "-rf", str(p)], check=True)
            for path, data in files.items():
                target = root / path
                if data is None:
                    target.unlink()
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            run("add", "-A")
            run("commit", "-qm", message, "--allow-empty")
            rev = git(root, "rev-parse", "HEAD").strip()
            run("checkout", "-q", "-f", "main")
            return rev

        def pin_files(manifest, image_src, tag, digest, rollback=False):
            return {f"{PINS_DIR}/admin-ui/{k}": v
                    for k, v in render(root, "admin-ui", manifest, image_src, tag, digest, rollback).items()}

        base = deploy_commit(None, {"README.md": b"deploy branch\n"}, "root")
        p1 = deploy_commit(base, pin_files(m1, m1, "t1", d1))

        def expect(name, parent, files, ok, fragment=""):
            try:
                rev = deploy_commit(parent, files)
                check_commit(root, rev, parent, "main")
                outcome = None
            except PinError as error:
                outcome = str(error)
            except Exception as error:  # a crash is a failure, never a rejection
                failures.append(f"{name}: crashed: {error!r}")
                return
            if ok and outcome is not None:
                failures.append(f"{name}: expected PASS, got {outcome}")
            elif not ok and outcome is None:
                failures.append(f"{name}: expected FAIL, got PASS")
            elif not ok and fragment not in outcome:
                failures.append(f"{name}: failed for the wrong reason: {outcome}")

        # The bootstrap root commit (README only) is checked by check_range, not here.
        expect("first pin", base, pin_files(m2, m1, "t1", d1), ok=True)
        expect("first pin at a stale main commit", base, pin_files(m1, m1, "t1", d1), ok=False,
               fragment="first pin copies manifests")
        expect("newer image + manifests", p1, pin_files(m2, m2, "t2", d2), ok=True)
        expect("manifest-only re-pin keeps image", p1, pin_files(m2, m1, "t1", d1), ok=True)
        expect("rollback to older image", deploy_commit(p1, pin_files(m2, m2, "t2", d2)),
               pin_files(m2, m1, "t1", d1, rollback=True), ok=True)
        expect("rollback to an image never pinned", deploy_commit(p1, pin_files(m2, m2, "t2", d2)),
               pin_files(m2, m1, "t9", "sha256:" + "9" * 64, rollback=True), ok=False,
               fragment="never pinned")
        expect("older build overwrites newer pin",
               deploy_commit(p1, pin_files(m2, m2, "t2", d2)), pin_files(m2, m1, "t1", d1),
               ok=False, fragment="image_source_sha moves backwards")
        expect("older manifests even on rollback",
               deploy_commit(p1, pin_files(m2, m2, "t2", d2)), pin_files(m1, m1, "t1", d1, rollback=True),
               ok=False, fragment="manifest_sha moves backwards")
        expect("path outside pins/", p1, {"evil.yaml": b"x\n"}, ok=False, fragment="outside pins/")
        no_digest = pin_files(m2, m2, "t2", d2)
        pin = json.loads(no_digest[f"{PINS_DIR}/admin-ui/pin.json"])
        pin["digest"] = ""
        no_digest[f"{PINS_DIR}/admin-ui/pin.json"] = json.dumps(pin).encode()
        expect("digest missing", p1, no_digest, ok=False, fragment="digest missing")
        edited = pin_files(m2, m2, "t2", d2)
        edited[f"{PINS_DIR}/admin-ui/manifests/admin-ui.yaml"] += b"privileged: true\n"
        expect("hand-edited manifest", p1, edited, ok=False, fragment="differs from what main")
        swapped = pin_files(m2, m2, "t2", d2)
        swapped[f"{PINS_DIR}/admin-ui/kustomization.yaml"] = swapped[
            f"{PINS_DIR}/admin-ui/kustomization.yaml"].replace(d2.encode(), d1.encode())
        expect("kustomization disagrees with pin.json", p1, swapped, ok=False, fragment="differs from what main")
        extra = pin_files(m2, m2, "t2", d2)
        extra[f"{PINS_DIR}/admin-ui/manifests/extra.yaml"] = b"kind: ClusterRoleBinding\n"
        expect("extra manifest", p1, extra, ok=False, fragment="file set differs")
        expect("source not on main", p1, pin_files(side, side, "t2", d2), ok=False,
               fragment="not an ancestor of main")
        expect("pin removed", p1, {f"{PINS_DIR}/admin-ui/pin.json": None}, ok=False, fragment="pin removed")
        other = {f"{PINS_DIR}/ledger/pin.json": b"{}"}
        expect("unpinnable component", p1, other, ok=False, fragment="pin.json must have exactly")

        # Slice 2: a service component is pinnable by derivation from its manifests on main.
        svc_image = f"{REGISTRY}/openbank-aml-service"
        aml = root / COMPONENTS_DIR / "aml"
        aml.mkdir(parents=True)
        aml_yaml = (f"kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n"
                    f"        - name: aml\n          image: \"{svc_image}:sandbox-old@{d1}\"\n"
                    f"        - name: opa\n          image: openpolicyagent/opa:1.0.0\n")
        (aml / "aml-service.yaml").write_text(aml_yaml)
        apps = root / "openbank-infra/gitops/apps"
        apps.mkdir(parents=True)
        aml_app = ("kind: Application\nspec:\n  source:\n    targetRevision: main\n"
                   "    repoURL: git@github.com:JiRaska/open-bank-oss.git\n"
                   f"    path: {COMPONENTS_DIR}/aml\n")
        (apps / "aml.yaml").write_text(aml_app)
        multi = root / COMPONENTS_DIR / "payments"
        multi.mkdir(parents=True)
        (multi / "payments.yaml").write_text(
            f"image: {REGISTRY}/openbank-sepa-payment:x\n---\nimage: {REGISTRY}/openbank-swift-service:x\n")
        (aml / "aml_rest_ext.rego").write_text("package x\n")
        (aml / "gen-aml-opa-bundle.sh").write_text("#!/bin/sh\n")
        (aml / "policy").mkdir()
        (aml / "policy" / "p.yaml").write_text("kind: ConfigMap\n")
        kz = root / COMPONENTS_DIR / "temporal"
        kz.mkdir(parents=True)
        (kz / "kustomization.yaml").write_text("resources: [t.yaml]\n")
        (kz / "t.yaml").write_text(f"image: {REGISTRY}/openbank-temporal-worker:x\n")
        bare = root / COMPONENTS_DIR / "kafka"
        bare.mkdir(parents=True)
        (bare / "kafka.yaml").write_text("image: quay.io/strimzi/kafka:0.45.0\n")
        run("add", "-A")
        run("commit", "-qm", "service components")
        m4 = git(root, "rev-parse", "HEAD").strip()
        (aml / "aml-service.yaml").write_text(aml_yaml + "metadata: {name: aml}\n")
        run("commit", "-qam", "aml manifest change")
        m5 = git(root, "rev-parse", "HEAD").strip()

        def svc_files(component, manifest, image_src, tag, digest, rollback=False):
            return {f"{PINS_DIR}/{component}/{k}": v
                    for k, v in render(root, component, manifest, image_src, tag, digest, rollback).items()}

        aml_pin = svc_files("aml", m5, m4, "t1", d1)
        expect("derived first pin at a stale main commit", p1, svc_files("aml", m4, m4, "t1", d1),
               ok=False, fragment="first pin copies manifests")
        if json.loads(aml_pin[f"{PINS_DIR}/aml/pin.json"])["image"] != svc_image:
            failures.append("derived image: aml did not derive openbank-aml-service")
        copied = sorted(k.split("/", 2)[2] for k in aml_pin)
        if copied != ["kustomization.yaml", "manifests/aml-service.yaml", "pin.json"]:
            failures.append(f"derived copy must be exactly what Argo renders, got {copied}")
        expect("derived single-image service component", p1, aml_pin, ok=True)
        for comp_name, fragment in (("payments", "exactly one"), ("kafka", "exactly one"),
                                    ("temporal", "is a kustomization"),
                                    ("nonexistent", "not a pinnable component")):
            try:
                render(root, comp_name, m4, m4, "t1", d1)
                failures.append(f"render {comp_name}: expected rejection, got a pin")
            except PinError as error:
                if fragment not in str(error):
                    failures.append(f"render {comp_name}: wrong reason {error}")
        forged = dict(aml_pin)
        forged_pin = json.loads(forged[f"{PINS_DIR}/aml/pin.json"])
        forged_pin["image"] = f"{REGISTRY}/openbank-ledger-service"
        forged[f"{PINS_DIR}/aml/pin.json"] = (json.dumps(forged_pin, indent=2, sort_keys=True) + "\n").encode()
        expect("pin.json names an image the manifests do not", p1, forged, ok=False,
               fragment="differs from what main")
        foreign = dict(aml_pin)
        foreign_pin = json.loads(foreign[f"{PINS_DIR}/aml/pin.json"])
        foreign_pin["image"] = "docker.io/evil/aml"
        foreign[f"{PINS_DIR}/aml/pin.json"] = json.dumps(foreign_pin).encode()
        expect("pin.json names a non-fleet image", p1, foreign, ok=False, fragment="not a fleet-registry image")

        a1 = deploy_commit(p1, aml_pin)

        def tip():
            return git(root, "rev-parse", "main").strip()

        # Held service: put openbank-admin-ui under a hold on main, then try to move its image.
        # The aml hold is keyed by the IMAGE repository (openbank-aml-service), which is not
        # "openbank-" + the component directory: a component-derived key would miss it.
        held = yaml.safe_load(rules)
        held["deploy_holds"]["services"]["openbank-admin-ui"] = {
            "issue": "#1", "reason": "self-test", "release_condition": "self-test"}
        held["deploy_holds"]["services"]["openbank-aml-service"] = {
            "issue": "#1", "reason": "self-test", "release_condition": "self-test",
            "approved_image": f"openbank-aml-service:t1@{d1}"}
        (root / "openbank-libs/governance/rules.yaml").write_text(yaml.safe_dump(held))
        run("commit", "-qam", "hold admin-ui")
        m3 = git(root, "rev-parse", "HEAD").strip()
        expect("held service image change", p1, pin_files(m2, m2, "t2", d2), ok=False, fragment="deploy hold")
        expect("held service manifest-only re-pin", p1, pin_files(m3, m1, "t1", d1), ok=True)
        expect("held derived service image change (hold keyed by image, not component)", a1,
               svc_files("aml", m3, m3, "t2", d2), ok=False, fragment="deploy hold")
        expect("held derived service manifest-only re-pin", a1, svc_files("aml", m3, m4, "t1", d1), ok=True)
        held["deploy_holds"]["services"]["openbank-aml-service"]["approved_image"] = \
            f"openbank-aml-service:t1@sha256:{'9' * 64}"
        (root / "openbank-libs/governance/rules.yaml").write_text(yaml.safe_dump(held))
        run("commit", "-qam", "approve a different aml image")
        expect("held service pin differs from approved_image", a1, svc_files("aml", tip(), m4, "t1", d1),
               ok=False, fragment="differs from approved_image")
        held["deploy_holds"]["services"]["openbank-aml-service"]["approved_image"] = \
            f"openbank-aml-service:t1@{d1}"
        (root / "openbank-libs/governance/rules.yaml").write_text(yaml.safe_dump(held))
        run("commit", "-qam", "restore approval")

        # Argo directory semantics: a recursing / filtering source renders a different file set.
        for options in ("    directory:\n      recurse: true\n", "    directory:\n      include: '*.yaml'\n",
                        "    directory:\n      exclude: 'x.yaml'\n"):
            (apps / "aml.yaml").write_text(aml_app + options)
            run("commit", "-qam", "argo options")
            expect(f"argo source options {options.split()[1]}", a1, svc_files("aml", tip(), m4, "t1", d1),
                   ok=False, fragment="source options")
        (apps / "aml.yaml").write_text("kind: Application\nspec:\n  source:\n    path: elsewhere\n")
        run("commit", "-qam", "no app")
        expect("no Application renders the component", a1, svc_files("aml", tip(), m4, "t1", d1),
               ok=False, fragment="no Argo Application")
        (apps / "aml.yaml").write_text(aml_app + "    directory: {}\n")
        run("commit", "-qam", "plain app")
        expect("plain directory source with empty options", a1, svc_files("aml", tip(), m4, "t1", d1), ok=True)

        # Path normalisation, repoURL, multi-source and ApplicationSet: each variant renders
        # the component on Argo and must reach the option check rather than escape it.
        repo = "    repoURL: https://github.com/JiRaska/open-bank-oss\n"
        recurse = "    directory:\n      recurse: true\n"
        elsewhere = root / "openbank-infra/gitops/clusters/x"
        elsewhere.mkdir(parents=True)
        for label, where, body, fragment in (
                ("leading ./ path", "apps", f"kind: Application\nspec:\n  source:\n{repo}"
                 f"    path: ./{COMPONENTS_DIR}/aml\n{recurse}", "source options"),
                ("double-slash trailing-slash path", "apps", "kind: Application\nspec:\n  source:\n"
                 f"{repo}    path: openbank-infra//gitops/components/aml/\n{recurse}", "source options"),
                ("dot-dot path", "apps", "kind: Application\nspec:\n  source:\n"
                 f"{repo}    path: {COMPONENTS_DIR}/../components/aml\n{recurse}", "source options"),
                ("multi-source", "apps", "kind: Application\nspec:\n  sources:\n"
                 f"    - repoURL: https://github.com/JiRaska/open-bank-oss\n      path: {COMPONENTS_DIR}/aml\n"
                 "    - repoURL: https://github.com/JiRaska/open-bank-oss\n      path: pins/aml\n",
                 "several sources"),
                ("ApplicationSet template", "clusters", "kind: ApplicationSet\nspec:\n  generators: []\n"
                 "  template:\n    spec:\n      source:\n        repoURL: https://github.com/JiRaska/open-bank-oss\n"
                 f"        path: {COMPONENTS_DIR}/aml\n        directory:\n          recurse: true\n",
                 "source options"),
                ("foreign repoURL", "clusters", "kind: Application\nspec:\n  source:\n"
                 f"    repoURL: https://github.com/evil/fork\n    path: {COMPONENTS_DIR}/aml\n",
                 "not this repository"),
                ("recursing parent directory", "clusters", "kind: Application\nspec:\n  source:\n"
                 f"{repo}    path: openbank-infra/gitops/components\n{recurse}", "recursively")):
            extra_app = (apps if where == "apps" else elsewhere) / "extra.yaml"
            extra_app.write_text(body)
            run("add", "-A")
            run("commit", "-qm", f"argo variant {label}")
            expect(f"argo {label}", a1, svc_files("aml", tip(), m4, "t1", d1), ok=False, fragment=fragment)
            extra_app.unlink()
            run("add", "-A")
            run("commit", "-qm", f"drop {label}")
        expect("plain directory source after variants", a1, svc_files("aml", tip(), m4, "t1", d1), ok=True)

        # Round 3: constructs whose rendered path Argo computes and this checker does not.
        # Each must fail closed; none is emulated.
        appset = ("kind: ApplicationSet\nspec:\n  generators:\n{gens}  template:\n    spec:\n"
                  "      source:\n        repoURL: {repo}\n        path: {path}\n{extra}")
        listgen = "    - list:\n        elements: [{a: b}]\n"
        gitgen = "    - git:\n        repoURL: x\n        directories:\n          - path: " \
                 f"{COMPONENTS_DIR}/*\n"
        ours = "https://github.com/JiRaska/open-bank-oss"
        for label, body, fragment in (
                ("templated path", appset.format(gens=listgen, repo=ours, extra="",
                 path=f"'{COMPONENTS_DIR}/{{{{name}}}}'"), "templated source"),
                ("templated path without static prefix", appset.format(gens=listgen, repo=ours, extra="",
                 path="'{{path}}'"), "no static prefix"),
                ("templated repoURL", appset.format(gens=listgen, repo="'{{repo}}'", extra="",
                 path=f"{COMPONENTS_DIR}/aml"), "templated source"),
                ("templatePatch", appset.format(gens=listgen, repo=ours, path="openbank-infra/gitops/apps",
                 extra="  templatePatch: '{}'\n"), "templatePatch"),
                ("git directories generator over components/*", appset.format(
                    gens=gitgen, repo=ours, extra="", path="openbank-infra/gitops/apps"), "git generator"),
                ("matrix generator", appset.format(gens="    - matrix:\n        generators:\n"
                 "          - list: {elements: []}\n", repo=ours, extra="",
                 path=f"{COMPONENTS_DIR}/aml"), "matrix generator"),
                ("plugin generator over the parent", appset.format(gens="    - plugin: {configMapRef: "
                 "{name: x}}\n", repo=ours, extra="", path=COMPONENTS_DIR), "plugin generator"),
                ("backslash path", "kind: Application\nspec:\n  source:\n"
                 f"{repo}    path: 'openbank-infra\\gitops\\components\\aml'\n", "backslash"),
                ("jsonnet source option", "kind: Application\nspec:\n  source:\n"
                 f"{repo}    path: {COMPONENTS_DIR}/aml\n    directory:\n      jsonnet: {{libs: [x]}}\n",
                 "jsonnet options")):
            extra_app = elsewhere / "round3.yaml"
            extra_app.write_text(body)
            run("add", "-A")
            run("commit", "-qm", f"round3 {label}")
            expect(f"argo {label}", a1, svc_files("aml", tip(), m4, "t1", d1), ok=False,
                   fragment=fragment)
            extra_app.unlink()
            run("add", "-A")
            run("commit", "-qm", f"drop {label}")
        unrelated = appset.format(gens=listgen, repo=ours, extra="", path="'openbank-infra/gitops/other/{{n}}'")
        (elsewhere / "round3.yaml").write_text(unrelated)
        run("add", "-A")
        run("commit", "-qm", "unrelated templated appset")
        expect("templated ApplicationSet elsewhere does not block aml", a1, svc_files("aml", tip(), m4, "t1", d1), ok=True)
        (elsewhere / "round3.yaml").unlink()
        run("add", "-A")
        run("commit", "-qm", "drop unrelated appset")

        # Jsonnet file and symlinks in the component tree, or in a source path.
        def tree_case(label, mutate, undo, fragment):
            pin_now = svc_files("aml", tip(), m4, "t1", d1)
            mutate()
            run("add", "-A")
            run("commit", "-qm", f"tree {label}")
            expect(f"tree {label}", a1, pin_now, ok=False, fragment=fragment)
            try:
                render(root, "aml", tip(), m4, "t1", d1)
                failures.append(f"render {label}: expected rejection, got a pin")
            except PinError as error:
                if fragment not in str(error):
                    failures.append(f"render {label}: wrong reason {error}")
            undo()
            run("add", "-A")
            run("commit", "-qm", f"undo {label}")
        tree_case("jsonnet file", lambda: (aml / "x.jsonnet").write_text("{}\n"),
                  lambda: (aml / "x.jsonnet").unlink(), "jsonnet")
        tree_case("libsonnet in a subdirectory", lambda: (aml / "policy" / "l.libsonnet").write_text("{}\n"),
                  lambda: (aml / "policy" / "l.libsonnet").unlink(), "jsonnet")
        tree_case("symlinked manifest file", lambda: (aml / "link.yaml").symlink_to("aml-service.yaml"),
                  lambda: (aml / "link.yaml").unlink(), "symlink")
        tree_case("symlink to a non-manifest", lambda: (aml / "notes.txt").symlink_to("/etc/passwd"),
                  lambda: (aml / "notes.txt").unlink(), "symlink")
        real_aml = root / "aml-real"

        def link_dir():
            aml.rename(real_aml)
            aml.symlink_to(real_aml)

        def unlink_dir():
            aml.unlink()
            real_aml.rename(aml)
        tree_case("symlinked component dir", link_dir, unlink_dir, "symlink")
        pin_now = svc_files("aml", tip(), m4, "t1", d1)
        (root / "openbank-infra/gitops/alias").symlink_to("components")
        (elsewhere / "round3.yaml").write_text("kind: Application\nspec:\n  source:\n"
                                              f"{repo}    path: openbank-infra/gitops/alias/aml\n")
        run("add", "-A")
        run("commit", "-qm", "source through a symlinked directory")
        expect("argo source path through a symlink", a1, pin_now, ok=False, fragment="symlink")
        (root / "openbank-infra/gitops/alias").unlink()
        (elsewhere / "round3.yaml").unlink()
        run("add", "-A")
        run("commit", "-qm", "drop symlinked source")
        expect("plain directory source after round 3", a1, svc_files("aml", tip(), m4, "t1", d1), ok=True)

        # Parsed image derivation: shapes a line regex misses must not yield a single image.
        good = {"d.yaml": aml_yaml.encode()}
        if derived_image(good) != svc_image:
            failures.append(f"derived_image: block-style single image not derived: {derived_image(good)}")
        second = f"{REGISTRY}/openbank-ledger-service:x"
        same = f"{svc_image}:z"  # same repository: only the differential guards can reject it
        for label, extra in (
                ("flow-style second image", {"f.yaml": (
                    "kind: Pod\nspec: {containers: [{name: x, image: '" + second + "'}]}\n").encode()}),
                ("JSON second image", {"j.json": json.dumps({"kind": "Deployment", "spec": {"template": {
                    "spec": {"containers": [{"name": "x", "image": second}]}}}}).encode()}),
                ("initContainer second image", {"i.yaml": (
                    "kind: Deployment\nspec:\n  template:\n    spec:\n      initContainers:\n"
                    f"        - name: x\n          image: {second}\n").encode()}),
                ("unattributed registry mention", {"c.yaml": (
                    f"kind: ConfigMap\ndata:\n  x: {second}\n").encode()}),
                ("unparseable manifest", {"u.yaml": b"kind: [unclosed\n"}),
                # Parser differentials: the raw byte count and the parse must not be fooled.
                ("escaped registry string", {"e.yaml": (
                    "kind: Pod\nspec:\n  containers:\n    - name: x\n      image: \"2\\x36"
                    + same[2:] + "\"\n").encode()}),
                ("escaped registry in annotation", {"e2.yaml": (
                    "kind: ConfigMap\nmetadata:\n  annotations:\n    a: \"2\\u0036"
                    + same[2:] + "\"\n").encode()}),
                ("!!binary-encoded image", {"b.yaml": (
                    "kind: Pod\nspec:\n  containers:\n    - name: x\n      image: !!binary "
                    + base64.b64encode(same.encode()).decode() + "\n").encode()}),
                ("!!binary in a ConfigMap", {"b2.yaml": (
                    "kind: ConfigMap\nbinaryData:\n  x: !!binary "
                    + base64.b64encode(same.encode()).decode() + "\n").encode()}),
                ("custom tag", {"t.yaml": b"kind: ConfigMap\ndata: !custom {x: y}\n"}),
                ("!!set hiding an escaped registry string", {"s.yaml": (
                    "kind: ConfigMap\nmetadata:\n  annotations:\n    x: !!set {\"2\\x36"
                    + same[2:] + "\": null}\n").encode()}),
                ("registry string in an annotation", {"a.yaml": (
                    f"kind: ConfigMap\nmetadata:\n  annotations:\n    img: {same}\n").encode()}),
                ("registry string as a mapping key", {"k.yaml": (
                    f"kind: ConfigMap\ndata:\n  \"{same}\": x\n").encode()}),
                ("duplicate image key", {"dk.yaml": (
                    "kind: Pod\nspec:\n  containers:\n    - name: x\n"
                    f"      image: {same}\n      image: {svc_image}:y\n").encode()})):
            if derived_image({**good, **extra}) is not None:
                failures.append(f"derived_image: {label} still derived a single image")

        aliased = {"d.yaml": (
            f"kind: Deployment\nspec:\n  template:\n    metadata:\n      annotations:\n"
            f"        first: &img \"{svc_image}:t\"\n    spec:\n      containers:\n"
            f"        - name: x\n          image: *img\n").encode()}
        if derived_image(aliased) is not None:
            failures.append("derived_image: alias reuse of an image into an annotation still derived")
        aliased2 = {"d.yaml": (
            f"kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n"
            f"        - name: x\n          image: &img \"{svc_image}:t\"\n          env:\n"
            f"            - name: y\n              value: *img\n").encode()}
        if derived_image(aliased2) is not None:
            failures.append("derived_image: alias reuse of an image into an env value still derived")
        folded = {"d.yaml": (
            "kind: Pod\nspec:\n  containers:\n    - name: x\n      image: \"" + svc_image + ":t\"\n"
            "metadata:\n  annotations:\n    n: \"" + REGISTRY[:10] + "\\\n      " + REGISTRY[10:]
            + "/openbank-ledger-service\"\n").encode()}
        if derived_image(folded) is not None:
            failures.append("derived_image: line-continued registry string in an annotation still derived")

        # The image must be the one main runs NOW, not the one at the pin's manifest_sha.
        before_move = tip()
        (aml / "aml-service.yaml").write_text(aml_yaml.replace("openbank-aml-service", "openbank-aml-v2"))
        run("commit", "-qam", "aml image repository changes on main")
        expect("image is not the tip image", a1, svc_files("aml", before_move, m4, "t1", d1), ok=False,
               fragment="runs on main now")

        # State mode: a bad pin planted earlier and left untouched by the tip.
        planted = pin_files(m2, m2, "t2", d2)
        planted[f"{PINS_DIR}/admin-ui/manifests/admin-ui.yaml"] += b"privileged: true\n"
        bad_pin = deploy_commit(p1, planted)
        tip = deploy_commit(bad_pin, {"README.md": b"touch\n"})
        try:
            check_state(root, tip, "main")
            failures.append("check_state: planted bad pin PASSED")
        except Exception as error:
            if not isinstance(error, PinError) or "tip state differs" not in str(error):
                failures.append(f"check_state: wrong reason {error!r}")
        try:
            check_state(root, p1, "main")
        except Exception as error:
            failures.append(f"check_state: good tip failed {error!r}")
        expect("empty commit", p1, {}, ok=False, fragment="changes nothing")

        # check_range must walk every commit, not only the tip: a bad commit buried under a
        # good one is still a violation of the branch history.
        bad = deploy_commit(p1, {"evil.yaml": b"x\n"})
        good_on_bad = deploy_commit(bad, {"evil.yaml": None})
        try:
            check_range(root, p1, good_on_bad, "main")
            failures.append("check_range: buried bad commit PASSED")
        except Exception as error:
            if not isinstance(error, PinError) or "outside pins/" not in str(error):
                failures.append(f"check_range: wrong reason {error!r}")

        # Pre-bootstrap: an absent branch is NOT_EVALUATED with a non-zero exit. Exit 0 with
        # SUBJECTS=UNRESOLVED renders PASS in run-gates.py (the floor is skipped, rc stays 0).
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = main(["check", "--root", str(root), "--head", "refs/heads/absent-branch",
                       "--allow-absent"])
        lines = buf.getvalue().strip().splitlines()
        if rc == 0 or not lines or lines[-1] != "GATE_VERDICT=NOT_EVALUATED":
            failures.append(f"absent branch: rc={rc}, output {buf.getvalue().strip()!r} must end "
                            f"GATE_VERDICT=NOT_EVALUATED with a non-zero exit")

    failures.extend(render_real_component())
    for failure in failures:
        print(f"SELF-TEST FAIL: {failure}")
    print(f"self-test: {'FAILED' if failures else 'ok'} ({len(failures)} failure(s))")
    return 1 if failures else 0


def render_real_component():
    """Build a pin from the REAL components/admin-ui at HEAD and run kustomize over it: the
    output must name the image by the pinned digest, and the image name in PINNABLE must be
    exactly the string the manifest uses (kustomize matches `images.name` verbatim; a mismatch
    renders silently with the old tag)."""
    failures = []
    real = Path(__file__).resolve().parents[2]
    head = git(real, "rev-parse", "HEAD").strip()
    derived = {}
    for component in ("aml", "ledger", "pension-fund"):
        try:
            files = component_files(real, head, component)
        except PinError as error:
            failures.append(f"render {component}: {error}")
            continue
        image = derived_image(files)
        if image is None:
            failures.append(f"render {component}: no single derived image (the derivation regex "
                            f"no longer matches the real manifests)")
        else:
            derived[component] = image
    for component, image in {**PINNABLE, **derived}.items():
        manifests = (real / COMPONENTS_DIR / component).glob("*.yaml")
        refs = {m.group(1) for f in manifests
                for m in re.finditer(r"image:\s*[\"']?(\S+?)(?::[^\s@\"']+)?(?:@[^\s\"']+)?[\"']?\s*$", f.read_text(), re.M)
                if m.group(1).startswith(REGISTRY + "/openbank-")}
        if component in PINNABLE and refs != {image}:
            failures.append(f"render {component}: manifest image name(s) {sorted(refs)} != PINNABLE {image!r}")
            continue
        tool = next((t for t in (["kustomize", "build"], ["kubectl", "kustomize"])
                     if subprocess.run(["sh", "-c", f"command -v {t[0]}"], capture_output=True).returncode == 0),
                    None)
        if tool is None:
            if os.environ.get("CI"):
                failures.append(f"render {component}: no kustomize or kubectl on the CI runner")
            else:
                print(f"self-test: render {component} skipped locally (no kustomize/kubectl)")
            continue
        digest = "sha256:" + "d" * 64
        files = render(real, component, git(real, "rev-parse", "HEAD").strip(),
                       git(real, "rev-parse", "HEAD").strip(), "selftest-tag", digest)
        with tempfile.TemporaryDirectory() as out:
            for path, data in files.items():
                (Path(out) / path).parent.mkdir(parents=True, exist_ok=True)
                (Path(out) / path).write_bytes(data)
            built = subprocess.run([*tool, out], capture_output=True, text=True)
        if built.returncode:
            failures.append(f"render {component}: {' '.join(tool)} failed: {built.stderr.strip()[:300]}")
            continue
        rendered = {m.group(1) for m in re.finditer(r"image:\s*(\S+)", built.stdout)
                    if m.group(1).startswith(image)}
        want = f"{image}:selftest-tag@{digest}"
        if rendered != {want}:
            failures.append(f"render {component}: rendered {sorted(rendered)}, want exactly {want}")
        else:
            print(f"self-test: render {component} -> {want}")
    return failures


# ---------------------------------------------------------------- CLI

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="cmd")
    r = sub.add_parser("render", help="write pins/<component>/ into --out")
    r.add_argument("--root", default=".")
    r.add_argument("--component", required=True)
    r.add_argument("--manifest-sha", required=True)
    r.add_argument("--image-source-sha", required=True)
    r.add_argument("--tag", required=True)
    r.add_argument("--digest", required=True)
    r.add_argument("--rollback", action="store_true")
    r.add_argument("--out", required=True, help="directory that becomes pins/<component>/")
    r.add_argument("--json", help="also write {path: base64} for the GraphQL commit here")
    c = sub.add_parser("check", help="verify deploy-branch commits in BASE..HEAD")
    c.add_argument("--root", default=".")
    c.add_argument("--head", required=True)
    c.add_argument("--base", help="last already-verified commit (omit to verify the whole branch)")
    c.add_argument("--main", default="origin/main")
    c.add_argument("--tip-only", action="store_true",
                   help="verify only HEAD against its parent (the per-PR gate; a bad commit buried "
                        "under later good ones is the writer's pre-check's job, not every PR's)")
    c.add_argument("--state", action="store_true",
                   help="also verify every pin present at HEAD, not only what HEAD changed")
    c.add_argument("--allow-absent", action="store_true",
                   help="treat a head ref that does not resolve as zero subjects (pre-bootstrap)")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    try:
        if args.cmd == "render":
            files = render(args.root, args.component, args.manifest_sha, args.image_source_sha,
                           args.tag, args.digest, args.rollback)
            out = Path(args.out)
            for path, data in files.items():
                (out / path).parent.mkdir(parents=True, exist_ok=True)
                (out / path).write_bytes(data)
            if args.json:
                Path(args.json).write_text(json.dumps(
                    {path: base64.b64encode(data).decode() for path, data in sorted(files.items())}))
            print(f"rendered {len(files)} file(s) for {args.component} -> {out}")
            return 0
        if args.cmd == "check":
            exists = subprocess.run(["git", "-C", args.root, "rev-parse", "--verify", "--quiet",
                                     f"{args.head}^{{commit}}"], capture_output=True).returncode == 0
            if not exists:
                if args.allow_absent:
                    print(f"SUBJECTS=UNRESOLVED  # {args.head} does not exist yet (deploy/sandbox not "
                          f"bootstrapped by the owner); nothing to verify, which is not a pass")
                    # Exit 0 here would render PASS in run-gates.py (UNRESOLVED only skips the
                    # floor). Same convention as check-ruleset-bypass-actors.py: NOT_EVALUATED
                    # is a non-zero exit, so the gate stays red until the owner bootstraps.
                    print("GATE_VERDICT=NOT_EVALUATED")
                    return 1
                raise PinError(f"{args.head} does not resolve")
            base = args.base
            if args.tip_only:
                parents = git(args.root, "rev-list", "--parents", "-n", "1", args.head).split()[1:]
                base = parents[0] if parents else None
            n = check_range(args.root, base, args.head, args.main)
            if args.state:
                check_state(args.root, args.head, args.main)
            print(f"SUBJECTS={n}  # deploy-branch commits verified")
            return 0
    except PinError as error:
        print(f"::error::deploy-pin-branch-contract: {error}")
        return 1
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
