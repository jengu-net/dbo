---
title: Technical
eyebrow: Technical
standfirst: >-
  What it needs from an environment, and how any number quoted about it was
  arrived at.
template: essay.html
---

**[What it needs to run](environment.md).** The dependencies are short and the
absences are deliberate: one language runtime, one database, no broker, no
cache tier, and a container orchestrator that is responsible for less than you
might expect.

**[How performance is measured](performance.md).** Not a benchmark page. A
description of the method — the hardware, the two profiles, what makes a run
invalid — so that a number from here can be argued with on how it was taken.

<div class="further" markdown>
Operating it end to end — bring-up, embedding, backup as export, reshape,
upgrades — is [Running it (§11)](../docs/arc42-008-crosscutting/running-it.md).
Routing and scaling are
[Deployment (§5)](../docs/arc42-007-deployment/README.md).
</div>
