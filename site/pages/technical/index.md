---
title: Technical
eyebrow: Technical
standfirst: >-
  How to have it running, what it needs from an environment, and how any
  number quoted about it was arrived at.
template: essay.html
---

**[A store running, in about a minute](quickstart-docker.md).** Docker and
nothing else. Every command on that page is executed on every build, so it is
the least likely thing here to be lying.

**[The same thing from source, and somewhere to watch it
think](quickstart-karaf.md).** Longer, and it buys something the container
cannot: a bundle set you can edit while it runs, a tenant on FHIR R5, and the
store's own internal process bringing that tenant up — visible twice, once as
a log line and once as the record the log is a copy of.

**[What it needs to run](environment.md).** The dependencies are short and the
absences are deliberate: one language runtime, one database, no broker, no
cache tier, and a container orchestrator that is responsible for less than you
might expect.

**[How performance is measured](performance.md).** Not a benchmark page. A
description of the method — the hardware, the two profiles, what makes a run
invalid — so that a number from here can be argued with on how it was taken.

<div class="further" markdown>
Operating it end to end — bring-up, embedding, backup as export, reshape,
upgrades — is [Running it](../docs/arc42-008-crosscutting/running-it/README.md).
Routing and scaling are
[Deployment](../docs/arc42-007-deployment/README.md).
</div>
