---
description: Test policy — no PII fixtures, fakes/real over mocks, no external calls, no redundant action tests, no controller re-tests of validated-primitive field validation, readable JSON, method-only descriptions
files: src/test/scala
---

Review changes under test sources. Flag:

- Any PII in fixtures or test data — real names, emails, addresses, phone/card
  numbers. Fixtures must be synthetic placeholders.
- A mock used outside an integration/controller test, or outside testing an
  external client's own parsing. Elsewhere use a fake implementation or the real
  one (Postgres via docker). Mocking an external response's JSON to check our
  client parses it is fine.
- A test that calls a real external service.
- Action-level tests for behaviour a controller test already exercises through
  that same action — don't duplicate; the controller test covers it.
- A controller/integration test that sends a malformed value for a request field
  whose type is a validated primitive (a `WrappedString` such as `Name`, `Email`,
  `CategoryName`, `CategoryDescription`) and asserts a specific validation message
  (e.g. `expectError must be("Name must be 25 characters or less")`). Such a field
  is validated at decode by the primitive's `Format`, so a bad value is rejected
  before the action runs and surfaces as a transport/decode error, not the action's
  clean message — the assertion cannot hold. The primitive's own spec covers its
  shape (blank, length, format); the controller test should cover the domain/action
  errors instead (ownership, conflicts, not-found). This does not apply to a
  free-text `String` field validated inside the action, whose clean message a
  controller test legitimately asserts.
- A single-line JSON payload — JSON in tests must be multi-line and
  human-readable.
- A test description qualified with a type name (`"DocumentParser.parse"`,
  `"BankStatementList.accountLabelOf"`) — name the method only (`"parse"`,
  `"accountLabelOf"`). A spec exercises one object's methods, so the type prefix is
  redundant; scalatest already prints the spec class.
- A test that reaches the database with hand-written SQL via
  `anorm.SQL("...").on("p" -> v)`. Match how the DAOs query: use the
  `SQL"""... ${param}"""` string interpolator (same binding, one consistent
  style), and prefer calling the existing repository/DAO over re-implementing
  its query inline.

Not: unit tests of pure logic using fakes; integration tests backed by docker
Postgres; a one-off `SQL"""...${param}"""` interpolated query that inspects
state no repository/DAO exposes.
