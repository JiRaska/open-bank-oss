"""Isolate operation responses before Schemathesis infers links (Refs #12696)."""
from copy import deepcopy
import re
from urllib.parse import unquote_to_bytes

import schemathesis


@schemathesis.hook
def before_load_schema(context, raw_schema):
    paths = raw_schema.get("paths")
    if not isinstance(paths, dict):
        return

    # YAML anchors can alias path items, operation objects, or `responses` maps.
    # Split each shared parent before assigning operation-local response copies.
    path_items = {}
    for path, path_item in paths.items():
        if isinstance(path_item, dict):
            path_items.setdefault(id(path_item), []).append((path, path_item))
    for aliases in path_items.values():
        if len(aliases) > 1:
            for path, path_item in aliases:
                paths[path] = deepcopy(path_item)

    operation_sites = {}
    for path_item in paths.values():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if isinstance(method, str) and method.lower() in {
                "get", "put", "post", "delete", "options", "head", "patch", "trace"
            } and isinstance(operation, dict):
                operation_sites.setdefault(id(operation), []).append((path_item, method, operation))
    for aliases in operation_sites.values():
        if len(aliases) > 1:
            for path_item, method, operation in aliases:
                path_item[method] = deepcopy(operation)

    operations = []
    response_maps = {}
    for path_item in raw_schema.get("paths", {}).values():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if not isinstance(method, str) or method.lower() not in {
                "get", "put", "post", "delete", "options", "head", "patch", "trace"
            }:
                continue
            if not isinstance(operation, dict):
                continue
            operations.append(operation)
            responses = operation.get("responses")
            if isinstance(responses, dict):
                response_maps.setdefault(id(responses), []).append((operation, responses))

    for aliases in response_maps.values():
        if len(aliases) > 1:
            for operation, responses in aliases:
                operation["responses"] = deepcopy(responses)

    uses = {}
    for operation in operations:
        responses = operation.get("responses")
        if isinstance(responses, dict):
            for status, response in responses.items():
                if isinstance(response, dict) and isinstance(response.get("$ref"), str):
                    ref = response["$ref"]
                    pointer = _decode_local_response_pointer(ref)
                    if pointer is not None:
                        uses.setdefault(tuple(pointer), []).append((responses, status))

    for pointer, sites in uses.items():
        if len(sites) < 2:
            continue
        if any(
            not isinstance(responses[status], dict) or set(responses[status]) != {"$ref"}
            for responses, status in sites
        ):
            continue
        target = raw_schema
        try:
            for part in pointer:
                target = target[part]
        except (KeyError, TypeError):
            continue
        # Chained or malformed targets are left to Schemathesis' normal validator.
        if not isinstance(target, dict) or "$ref" in target:
            continue
        for responses, status in sites:
            responses[status] = deepcopy(target)


def _decode_local_response_pointer(ref):
    if not isinstance(ref, str) or not ref.startswith("#"):
        return None
    fragment = ref[1:]
    if re.search(r"%(?![0-9A-Fa-f]{2})", fragment):
        return None
    try:
        decoded = unquote_to_bytes(fragment).decode("utf-8")
    except UnicodeDecodeError:
        return None
    if not decoded.startswith("/components/responses/"):
        return None
    tokens = decoded[1:].split("/")
    if any(re.search(r"~(?![01])", token) for token in tokens):
        return None
    return [token.replace("~1", "/").replace("~0", "~") for token in tokens]
