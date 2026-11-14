---
description: Test policy — no PII fixtures, fakes/real over mocks, no external calls, action/controller test split (behaviour in action integration tests, controllers cover auth + encoding), no controller re-tests of validated-primitive field validation, readable JSON, method-only descriptions
files: src/test/scala
---

Review changes under test sources. Flag:

- Any PII in fixtures or test data — real names, emails, addresses, phone/card
  numbers. Fixtures must be synthetic placeholders.
- A mock used outside an integration/controller test, or outside testing an
  external client's own parsing. Elsewhere use a fake implementation or the real
  one (Postgres via docker). Mocking an external response's JSON to check our
  client parses it is fine. Mocking the feature's own action inside its controller
  test, to confirm the controller decodes, authorizes, and delegates, is expected
  under the action/controller split below and must not be flagged.
- A test that calls a real external service.
- A test that violates the action/controller split for a NEW backend feature. New
  features put behaviour in the action's own integration test (real Postgres via
  `PostgresSpec`), and the controller test covers only auth/permission and
  request/response encoding, mocking the action to confirm it is invoked with the
  decoded request. So do NOT flag an action integration test as duplicating a
  controller test, and do NOT ask a controller test to re-assert behaviour the
  action test owns. Flag instead a NEW feature's controller test that re-exercises
  the action's behaviour against a real DB, or an action whose behaviour has no
  integration test. Existing controllers that test behaviour through the real
  action are grandfathered and need not be rewritten.
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
