"""Truncate decimals the FHIR grammar does not allow.

Synthea writes its life-years extensions at full double precision --
0.0019263281655490874, nineteen digits after the point -- and the FHIR decimal
regex permits seventeen. So the value is not merely unusual, it is invalid
FHIR, and dbo refuses the transaction that carries it. HAPI and fhirest accept
it, which is the same asymmetry the dangling Provenance had: correcting it
once for all three measures the write path, and leaving it would measure whose
parser reads the grammar.

Targeted on purpose. Only literals with eighteen or more fractional digits are
touched -- a whole-document reparse would reformat numbers that were fine and
change what every server is being fed.
"""
import re, glob, os, sys

cohort = sys.argv[1]
pattern = re.compile(rb'(-?\d+\.\d{17})\d+')
report = ["# bundle\tdecimalsTruncated\n"]
total = 0
for f in sorted(glob.glob(os.path.join(cohort, "*.json"))):
    raw = open(f, "rb").read()
    fixed, n = pattern.subn(rb'\1', raw)
    if n:
        open(f, "wb").write(fixed)
    report.append(f"{os.path.basename(f)}\t{n}\n")
    total += n
open(os.path.join(cohort, "DECIMALS.tsv"), "w").writelines(report)
print(f"truncated {total} over-precision decimals across {len(report)-1} bundles")
