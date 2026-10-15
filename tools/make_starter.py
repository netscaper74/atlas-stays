#!/usr/bin/env python3
"""
Turns the solution into the attendee starter: the code between

    // ▶ LAB x.y
    ...
    // ◀ LAB x.y

becomes `throw Lab.todo("x.y");` (the markers stay, so attendees know where to write).
The removed code is saved to src/main/resources/labs/solutions.json, which powers the
"Solve it" button in the app's Setup panel. Run it from the repository root on a branch
you want to convert (the workshop repo keeps the solution on `solution` and the
starter on `main`):

    git checkout -b main solution
    python3 tools/make_starter.py
    git commit -am "Starter: exercises removed"

    python3 tools/make_starter.py --check   # only lists the exercises, changes nothing
    python3 tools/make_starter.py --export  # only writes solutions.json (used on the solution branch)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/java"
SOLUTIONS = Path(__file__).resolve().parent.parent / "src/main/resources/labs/solutions.json"
START = re.compile(r"^(\s*)// ▶ LAB (\S+)\s*$")
END = re.compile(r"^\s*// ◀ LAB (\S+)\s*$")


def header(lines, i):
    """The method around a block: its Javadoc and declaration, for the before/after viewer."""
    d = i - 1
    while d >= 0 and not lines[d].rstrip().endswith("{"):
        d -= 1
    start = d
    if start > 0 and lines[start - 1].strip().endswith("*/"):
        start -= 1
        while start > 0 and not lines[start].strip().startswith("/**"):
            start -= 1
    return [l.rstrip("\n") for l in lines[start:d + 1]]


def convert(path: Path, check: bool, solutions: dict):
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    out, found, i = [], [], 0
    while i < len(lines):
        m = START.match(lines[i])
        if not m:
            if END.match(lines[i]):
                sys.exit(f"{path}:{i + 1}: end marker without a start")
            out.append(lines[i])
            i += 1
            continue
        indent, lab = m.group(1), m.group(2)
        j = i + 1
        while j < len(lines) and not END.match(lines[j]):
            if START.match(lines[j]):
                sys.exit(f"{path}:{j + 1}: nested LAB marker")
            j += 1
        if j == len(lines) or END.match(lines[j]).group(1) != lab:
            sys.exit(f"{path}:{i + 1}: LAB {lab} has no matching end marker")
        # the block must be the whole rest of the method body, or the code after it is unreachable
        k = j + 1
        while k < len(lines) and lines[k].strip() == "":
            k += 1
        if k < len(lines) and lines[k].strip() != "}":
            sys.exit(f"{path}:{j + 1}: LAB {lab} must end the method body (found: {lines[k].strip()!r})")
        body = [l.rstrip("\n") for l in lines[i + 1:j]]
        is_todo = "".join(body).strip() == f'throw Lab.todo("{lab}");'
        if not is_todo:  # already a starter block: keep the solution we exported before
            solutions.setdefault(lab, []).append({
                "file": str(path.relative_to(ROOT)),
                "header": header(lines, i),
                "indent": indent,
                "lines": body,
            })
        out.append(lines[i])
        out.append(f'{indent}throw Lab.todo("{lab}");\n')
        out.append(lines[j])
        found.append(lab)
        i = j + 1
    if found and not check and not EXPORT_ONLY:
        text = "".join(out)
        if "import io.jug.stays.Lab;" not in text and "package io.jug.stays;" not in text:
            text = re.sub(r"(^package [\w.]+;\n)", r"\1\nimport io.jug.stays.Lab;\n", text, count=1, flags=re.M)
        path.write_text(text, encoding="utf-8")
    return found


EXPORT_ONLY = "--export" in sys.argv


def main():
    check = "--check" in sys.argv
    total, solutions = [], {}
    for f in sorted(ROOT.rglob("*.java")):
        labs = convert(f, check, solutions)
        if labs:
            print(f"{f.relative_to(ROOT)}: {', '.join(labs)}")
            total += labs
    verb = "found" if check else "exported" if EXPORT_ONLY else "replaced"
    print(f"{len(total)} exercise blocks {verb}: {', '.join(sorted(set(total)))}")
    if not check and solutions:
        # merge, so re-running on a half-solved starter never loses a reference solution
        merged = json.loads(SOLUTIONS.read_text(encoding="utf-8")) if SOLUTIONS.exists() else {}
        merged.update(solutions)
        solutions = merged
        SOLUTIONS.parent.mkdir(parents=True, exist_ok=True)
        SOLUTIONS.write_text(json.dumps(dict(sorted(solutions.items())), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"Saved {len(solutions)} reference solutions to {SOLUTIONS.relative_to(ROOT.parent.parent.parent)}")


if __name__ == "__main__":
    main()
