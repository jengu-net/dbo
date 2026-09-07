# Conformance

What each FHIR face does about the RESTful rules of its specification, one
page per version.

These pages are **generated** by `:core:conformance:test` and not written by
hand. They are also not read from a CapabilityStatement: each rule is observed
by driving the real HTTP surface against a real database, so a claim here is a
thing the server was seen to do rather than a thing it says about itself.

A rule marked out of scope is a boundary drawn on purpose, and the
CapabilityStatement declares the same boundary. What each tier of search
covers is in [the REQ catalogue](../arc42-006-runtime/req-catalogue.md).

- [**R4**](r4.md)
- [**R5**](r5.md)
- [**R6**](r6.md)
