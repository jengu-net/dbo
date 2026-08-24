"""Fill elements R5 requires that R4 did not, and the converter cannot invent.

R4 to R5 tightened cardinality in places. Device.udiCarrier.issuer is min=0 in
R4 and min=1 in R5, so a Device that was valid R4 converts into an invalid R5
and no automatic conversion can fix it -- the value was never in the source.

dbo validates against the R5 StructureDefinition on write and refuses;
HAPI and fhirest do not check cardinality and accept. Correcting the cohort
means all three ingest the same VALID R5, which is what a real importer would
have had to produce anyway.
"""
import json, glob, os, sys

cohort = sys.argv[1]
GS1 = "http://hl7.org/fhir/NamingSystem/gs1"
report = ["# bundle\tudiIssuerAdded\n"]
total = 0
for f in sorted(glob.glob(os.path.join(cohort, "*.json"))):
    b = json.load(open(f))
    added = 0
    for e in b.get("entry", []):
        r = e.get("resource", {})
        if r.get("resourceType") != "Device":
            continue
        for udi in r.get("udiCarrier", []):
            if not udi.get("issuer"):
                udi["issuer"] = GS1
                added += 1
    if added:
        json.dump(b, open(f, "w"), separators=(",", ":"))
    report.append(f"{os.path.basename(f)}\t{added}\n")
    total += added
open(os.path.join(cohort, "UDI.tsv"), "w").writelines(report)
print(f"added {total} udiCarrier.issuer values across {len(report)-1} bundles")
