#!/usr/bin/env python3
"""
Turns the solution into the attendee starter: every block between

    // ▶ LAB x.y
    ...
    // ◀ LAB x.y

becomes `throw Lab.todo("x.y");`. Run it from the repository root on a branch
you want to convert (the workshop repo keeps the solution on `solution` and the
starter on `main`):

    git checkout -b main solution
    python3 tools/make_starter.py
    git commit -am "Starter: exercises removed"

    python3 tools/make_starter.py --check   # only lists the exercises, changes nothing
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/java"
START = re.compile(r"^(\s*)// ▶ LAB (\S+)\s*$")
END = re.compile(r"^\s*// ◀ LAB (\S+)\s*$")


def convert(path: Path, check: bool):
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
        out.append(f'{indent}throw Lab.todo("{lab}");\n')
        found.append(lab)
        i = j + 1
    if found and not check:
        text = "".join(out)
        if "import io.jug.stays.Lab;" not in text and "package io.jug.stays;" not in text:
            text = re.sub(r"(^package [\w.]+;\n)", r"\1\nimport io.jug.stays.Lab;\n", text, count=1, flags=re.M)
        path.write_text(text, encoding="utf-8")
    return found


def main():
    check = "--check" in sys.argv
    total = []
    for f in sorted(ROOT.rglob("*.java")):
        labs = convert(f, check)
        if labs:
            print(f"{f.relative_to(ROOT)}: {', '.join(labs)}")
            total += labs
    print(f"{len(total)} exercise blocks {'found' if check else 'replaced'}: {', '.join(sorted(set(total)))}")


if __name__ == "__main__":
    main()
