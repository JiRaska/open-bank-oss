"""Offline exact-head donor merge. Does not access GitHub or authorize release."""
import argparse
import importlib.util
from pathlib import Path
import sys

import yaml

spec = importlib.util.spec_from_file_location('deploy_holds', Path(__file__).with_name('check-deploy-holds.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def splice_services(text, merged):
    """Replace only one explicit block map; preserve prefix/suffix byte-for-byte.

    Comments inside the replaced services block are not retained. Ambiguous flow,
    alias or anchor forms are refused rather than rewriting unrelated governance.
    """
    node = yaml.compose(text, Loader=gate.UniqueLoader)

    def child(mapping, name):
        if not isinstance(mapping, yaml.MappingNode):
            raise gate.InvalidHold("hold source must use explicit mappings")
        matches = [(key, value) for key, value in mapping.value if key.value == name]
        if len(matches) != 1:
            raise gate.InvalidHold(f"ambiguous {name} location")
        return matches[0]

    _, holds_node = child(node, "deploy_holds")
    key, value = child(holds_node, "services")
    lines = text.splitlines(keepends=True)
    line = lines[key.start_mark.line]
    match = gate.re.fullmatch(r"( +)services:[ \t]*(?:\{[ \t]*\})?[ \t]*(#[^\r\n]*)?(\r?\n)?", line)
    if not match or key.start_mark.column != len(match.group(1)):
        raise gate.InvalidHold("services must be an unambiguous explicit block mapping")
    indent = len(match.group(1))
    start_line = key.start_mark.line
    end_line = start_line + 1
    # Dedented comments belong to surrounding governance, not to the rewritten map.
    while end_line < len(lines):
        candidate = lines[end_line]
        if candidate.strip() and len(candidate) - len(candidate.lstrip(' ')) <= indent:
            break
        end_line += 1
    while end_line > start_line + 1 and not lines[end_line - 1].strip():
        end_line -= 1
    newline = "\r\n" if line.endswith("\r\n") else "\n"
    comment = " " + match.group(2) if match.group(2) else ""
    replacement = match.group(1) + "services:" + comment + newline
    dumped = yaml.safe_dump(merged, sort_keys=False, allow_unicode=True)
    replacement += ''.join(' ' * (indent + 2) + part + newline for part in dumped.splitlines())
    prefix = ''.join(lines[:start_line])
    suffix = ''.join(lines[end_line:])
    output = prefix + replacement + suffix
    before = yaml.load(text, Loader=gate.UniqueLoader)
    after = yaml.load(output, Loader=gate.UniqueLoader)
    if after['deploy_holds']['services'] != merged:
        raise gate.InvalidHold("services splice did not preserve merged constraints")
    # Anchors pointing into the replaced block must not silently alter other policy.
    before['deploy_holds']['services'] = {}
    after['deploy_holds']['services'] = {}
    if before != after:
        raise gate.InvalidHold("services splice changed unrelated governance values")
    return output


def merge(target_text, donor_text, pr, head, services):
    if type(pr) is not int or pr <= 0 or not gate.re.fullmatch(r'[0-9a-f]{40}', head):
        raise gate.InvalidHold('exact donor PR and full head required')
    target = gate.holds(target_text)
    donor = gate.holds(donor_text)
    selected = {}
    if not services or len(set(services)) != len(services):
        raise gate.InvalidHold('nonempty unique service selection required')
    for service in services:
        if service not in donor:
            # A draft-only hold has no durable owner-authored rule to preserve.
            # Do not invent conditions or silently downgrade the missing evidence.
            raise gate.InvalidHold(f'{service}: donor has no durable hold metadata')
    # Copy every durable donor condition, including services outside pin overlap.
    # The selected pins only establish that no state-only hold is being invented.
    for service in donor:
        values = []
        for raw in gate.constraints(donor[service], service):
            value = {field: raw[field] for field in gate.FIELDS}
            origins = {(o['pr'], o['head']) for o in raw.get('origins', [])}
            origins.add((pr, head))
            value['origins'] = [{'pr': n, 'head': h} for n, h in sorted(origins)]
            values.append(value)
        selected[service] = {'all_of': values}
    output = splice_services(target_text, gate.union_holds(target, selected))
    gate.holds(output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--donor', type=Path, required=True)
    parser.add_argument('--pr', type=int, required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('services', nargs='+')
    args = parser.parse_args()
    try:
        print(merge(sys.stdin.read(), args.donor.read_text(), args.pr, args.head, args.services), end='')
    except (gate.InvalidHold, OSError, UnicodeError) as error:
        print(f'hold union refused: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
