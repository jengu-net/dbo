#!/usr/bin/env python3
"""
generate-skills.py — project agent skills from the constraints documents.

A rule that governs how work is done here is stated exactly once, in a
constraints document, and projected from there. Two projections come out of
one source, which is the whole point: a rule cannot be true in one place and
stale in another.

  1. One SKILL.md per skill-block, into tools/dbo-conventions/ — a plugin an
     agent installs, so the rule arrives when the work that trips it starts.
  2. The trap section of CLAUDE.md, copied verbatim from the region the
     source document marks, so the file an agent reads first cannot drift
     from the document that owns the text.

A skill-block looks like this, anywhere in a constraints document:

    <!-- skill: dbo-example -->
    ```yaml
    name: dbo-example
    applies-when: >-
      When this skill should fire. This becomes the skill's description,
      which is the field that decides whether it is ever read at all.
    reference: docs/arc42-002-constraints/working-rules.md#an-anchor
    ```
    **Rules**
    - MUST do this.
    - MUST NOT do that.
    <!-- /skill -->

The prose region CLAUDE.md receives is marked in the same document:

    <!-- claude:begin -->
    ...prose...
    <!-- claude:end -->

Nothing here is hand-editable output. `./gradlew generateSkills` regenerates,
and `verifySkillProjection` fails the build when the committed projection and
the source disagree — the same discipline the promise catalogue's projection
is under.

Usage:  python3 scripts/generate-skills.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
OUT = ROOT / "tools" / "dbo-conventions"
CLAUDE_MD = ROOT / "CLAUDE.md"

PLUGIN = "dbo-conventions"

SKILL_BLOCK = re.compile(
    r"<!--\s*skill:\s*(?P<name>[\w-]+)\s*-->(?P<body>.*?)<!--\s*/skill\s*-->",
    re.DOTALL,
)
YAML_FENCE = re.compile(r"```ya?ml\s*(?P<yaml>.*?)```", re.DOTALL)
CLAUDE_REGION = re.compile(
    r"<!--\s*claude:begin\s*-->\n(?P<body>.*?)<!--\s*claude:end\s*-->", re.DOTALL
)
CLAUDE_TARGET = re.compile(
    r"(?P<open><!--\s*rules:begin[^>]*-->\n).*?(?P<close><!--\s*rules:end\s*-->)",
    re.DOTALL,
)


def parse_meta(text: str) -> dict:
    """The fixed skill-block shape only: scalar keys plus folded scalars. A
    YAML library would be a dependency bought for three keys."""
    out: dict[str, str] = {}
    lines = text.splitlines()
    key_re = re.compile(r"^(?P<key>[\w-]+):\s*(?P<val>.*)$")
    i = 0
    while i < len(lines):
        m = key_re.match(lines[i])
        if not m:
            i += 1
            continue
        key, val = m.group("key"), m.group("val").strip()
        if val in (">-", ">", "|", "|-"):
            i += 1
            folded: list[str] = []
            while i < len(lines) and (lines[i].startswith(("  ", "\t")) or not lines[i].strip()):
                if lines[i].strip():
                    folded.append(lines[i].strip())
                i += 1
            out[key] = " ".join(folded)
            continue
        out[key] = val
        i += 1
    return out


def extract_rules(body: str) -> str:
    """Everything after the **Rules** marker, which is the imperative half."""
    m = re.search(r"\*\*Rules\*\*\s*\n(?P<rules>.*)", body, re.DOTALL)
    return (m.group("rules") if m else "").strip()


def render_skill(name: str, meta: dict, rules: str, up: str) -> str:
    desc = meta.get("applies-when", "").strip()
    ref = meta.get("reference", "").strip()
    return (
        "---\n"
        f"name: {name}\n"
        f"description: {desc}\n"
        "---\n\n"
        f"# {name}\n\n"
        "> **Generated from the constraints documents — do not edit.** Change the\n"
        "> skill-block in the source document and run `./gradlew generateSkills`.\n\n"
        f"**Apply when:** {desc}\n\n"
        "## Rules\n\n"
        f"{rules}\n\n"
        "---\n\n"
        f"Where this is stated and argued: [`{ref}`]({up}{ref})\n"
    )


def render_readme(blocks) -> str:
    rows = "\n".join(f"- **{n}** ← `{md.relative_to(ROOT)}`" for n, _m, _r, md in blocks)
    return (
        f"# {PLUGIN} — generated skills\n\n"
        "> **Nothing in this folder is hand-edited.** Every skill is projected\n"
        "> from a skill-block in a constraints document by\n"
        "> `scripts/generate-skills.py`. To change one, edit the block in its\n"
        "> source document and run `./gradlew generateSkills`.\n\n"
        "These are the rules that a green build does not enforce. This store's\n"
        "characteristic defect compiles, resolves, publishes and then dies on\n"
        "first use, so the rules that catch it are the ones nothing else will.\n"
        "Each skill is a thin projection: when it applies, what it requires,\n"
        "and a link to the document that argues it.\n\n"
        "## Skills\n\n"
        f"{rows}\n\n"
        "## Installing\n\n"
        "**All of them**, which is the useful form because the set grows as\n"
        "documents gain skill-blocks: add a plugin marketplace pointing at this\n"
        "folder, or at the repository. The manifest is\n"
        "`.claude-plugin/marketplace.json`.\n\n"
        "**One of them:** install the single `skills/<name>/` directory.\n"
    )


def project_into_claude_md(source: Path, region: str) -> bool:
    """Copy the marked prose region into CLAUDE.md. Returns True if it moved."""
    if not CLAUDE_MD.exists():
        print("  WARN  no CLAUDE.md — skipping the prose projection")
        return False
    text = CLAUDE_MD.read_text(encoding="utf-8")
    m = CLAUDE_TARGET.search(text)
    if not m:
        print("  WARN  CLAUDE.md has no rules:begin/rules:end markers — skipping")
        return False
    rebuilt = f"{m.group('open')}{region.strip()}\n{m.group('close')}"
    updated = text[: m.start()] + rebuilt + text[m.end() :]
    if updated == text:
        return False
    CLAUDE_MD.write_text(updated, encoding="utf-8")
    print(f"  ✓ CLAUDE.md trap section  ← {source.relative_to(ROOT)}")
    return True


def prune_stale(skills_dir: Path, keep: set[str]) -> None:
    """A skill whose block was deleted must not survive as a committed file."""
    if not skills_dir.exists():
        return
    for child in sorted(skills_dir.iterdir()):
        if not child.is_dir() or child.name in keep:
            continue
        for p in sorted(child.rglob("*"), reverse=True):
            p.unlink() if p.is_file() else p.rmdir()
        child.rmdir()
        print(f"  - pruned {child.name} (its block is gone)")


def main() -> int:
    blocks: list[tuple[str, dict, str, Path]] = []
    claude_region: tuple[Path, str] | None = None

    for md in sorted(DOCS.rglob("*.md")):
        text = md.read_text(encoding="utf-8")
        region = CLAUDE_REGION.search(text)
        if region:
            if claude_region is not None:
                print(f"  ERROR two documents claim the CLAUDE.md region: "
                      f"{claude_region[0].relative_to(ROOT)} and {md.relative_to(ROOT)}")
                return 1
            claude_region = (md, region.group("body"))
        for m in SKILL_BLOCK.finditer(text):
            name = m.group("name")
            y = YAML_FENCE.search(m.group("body"))
            if not y:
                print(f"  ERROR {md.relative_to(ROOT)}: skill '{name}' has no yaml block")
                return 1
            meta = parse_meta(y.group("yaml"))
            if not meta.get("applies-when"):
                print(f"  ERROR {md.relative_to(ROOT)}: skill '{name}' states no applies-when, "
                      "so nothing would ever trigger it")
                return 1
            rules = extract_rules(m.group("body"))
            if not rules:
                print(f"  ERROR {md.relative_to(ROOT)}: skill '{name}' states no rules")
                return 1
            blocks.append((name, meta, rules, md))

    if not blocks:
        print("No skill-blocks found under docs/.")
        return 1

    names = [n for n, _m, _r, _md in blocks]
    if len(set(names)) != len(names):
        print(f"  ERROR two skills share a name: {sorted(n for n in names if names.count(n) > 1)}")
        return 1

    (OUT / ".claude-plugin").mkdir(parents=True, exist_ok=True)
    (OUT / "skills").mkdir(parents=True, exist_ok=True)
    prune_stale(OUT / "skills", set(names))

    (OUT / ".claude-plugin" / "plugin.json").write_text(json.dumps({
        "$schema": "https://json.schemastore.org/claude-code-plugin-manifest.json",
        "name": PLUGIN,
        "version": "0.1.0",
        "description": "Working rules for this store, projected from its constraints "
                       "documents. Generated — do not hand-edit.",
        "author": {"name": "jengu-net"},
        "keywords": ["dbo", "arc42", "osgi", "runtime", "conventions"],
    }, indent=2) + "\n", encoding="utf-8")

    (OUT / ".claude-plugin" / "marketplace.json").write_text(json.dumps({
        "name": PLUGIN,
        "owner": {"name": "jengu-net"},
        "plugins": [{"name": PLUGIN, "source": "./"}],
    }, indent=2) + "\n", encoding="utf-8")

    up = "../" * (len(OUT.relative_to(ROOT).parts) + 2)
    for name, meta, rules, md in blocks:
        d = OUT / "skills" / name
        d.mkdir(parents=True, exist_ok=True)
        (d / "SKILL.md").write_text(render_skill(name, meta, rules, up), encoding="utf-8")
        print(f"  ✓ {name:22s} ← {md.relative_to(ROOT)}")

    (OUT / "README.md").write_text(render_readme(blocks), encoding="utf-8")

    if claude_region:
        project_into_claude_md(*claude_region)
    else:
        print("  WARN  no document marks a claude:begin region")

    print(f"\nProjected {len(blocks)} skill(s) into {OUT.relative_to(ROOT)}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
