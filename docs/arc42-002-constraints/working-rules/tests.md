# Tests

A test is named for the behaviour it proves, as a sentence:
`aRestoredConsumerStandsAtTheHeadOfTheFeed`. The name is the claim and the
body is the evidence.

A test written to prove a fix has to be seen failing for the reason it was
written. Two ways a test passes regardless are common: a wait that outlives
the condition it races, and an assertion on text the answer contains anyway,
such as a search echoing its own query in a bundle with no results.

<!-- skill: dbo-tests -->
```yaml
name: dbo-tests
applies-when: >-
  Writing or naming a test, or writing one to prove a fix.
reference: docs/arc42-002-constraints/working-rules/tests.md
```
**Rules**
- MUST name a test as a sentence about the behaviour it proves.
- MUST run the negative for a test written to prove a fix: break the thing
  and watch the test go red.
- MUST NOT accept a pass from a wait that outlives its condition, or from an
  assertion on text the answer contains regardless.
<!-- /skill -->
