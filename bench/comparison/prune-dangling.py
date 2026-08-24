"""Remove references to entries the bundle does not contain.

The rowling population's stage-2 theming strips resources but leaves the
Provenance that lists them as targets, so every bundle carries a Provenance
pointing at things no longer there. dbo refuses such a transaction; HAPI and
fhirest accept it. Neither behaviour is wrong -- but feeding one server a
refusal and the others a write would measure referential strictness rather
than the write path, so the cohort is corrected once, offline, for all three.

Recorded rather than silent: PRUNED.tsv says what came out of each bundle.
"""
import json, glob, sys, re, os

cohort = sys.argv[1]
report = ["# bundle\tentries\tprunedTargets\n"]
total_pruned = 0
for f in sorted(glob.glob(os.path.join(cohort, "*.json"))):
    b = json.load(open(f))
    full = {e.get("fullUrl") for e in b.get("entry", [])}
    pruned = 0
    for e in b.get("entry", []):
        r = e["resource"]
        if r.get("resourceType") != "Provenance":
            continue
        keep = []
        for t in r.get("target", []):
            ref = t.get("reference")
            if ref and ref.startswith("urn:uuid:") and ref not in full:
                pruned += 1
            else:
                keep.append(t)
        if keep:
            r["target"] = keep
        elif "target" in r:
            del r["target"]
    if pruned:
        json.dump(b, open(f, "w"), separators=(",", ":"))
    report.append(f"{os.path.basename(f)}\t{len(b.get('entry',[]))}\t{pruned}\n")
    total_pruned += pruned
open(os.path.join(cohort, "PRUNED.tsv"), "w").writelines(report)
print(f"pruned {total_pruned} dangling Provenance targets across {len(report)-1} bundles")
