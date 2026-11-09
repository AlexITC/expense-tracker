---
description: Server architecture — external services behind their own client class, write-time tenant/ownership integrity in repositories, interface trait implementations kept in one file
files: server/src/main/scala
---

Review changes under `server/src/main/scala` for the rules below. Flag clear
cases on added/changed lines; name the offending code and what to do instead.

## Repositories enforce write-time tenant/ownership integrity

Verifying that a write cannot persist cross-tenant or dangling data is a
repository responsibility, not a layering violation — even though it reads as a
touch of domain logic. A repository may check the target row's ownership (via the
write's `WHERE id = ? AND user_id = ?`) and a referenced user-owned entity's
ownership, in the same transaction, calling another entity's **DAO** to do so
(downward, allowed — only sideways repository→repository calls are banned), and
raise the domain error itself. Do not flag that as misplaced business logic, and
do not flag an action for *omitting* such a check — it belongs below it. Input
shape/format validation and non-integrity rules still belong in the action.

## External services live behind their own class

A call to an external service — an AWS SDK (S3, Textract, Bedrock), an HTTP
client, any third-party client — must go through a dedicated client/service
class of its own, ideally in its own package. Flag an SDK or raw-client call
made directly inside a controller, action, or repository instead of behind a
named client type.

Not: a layer class depending on such a client type (that is how it should look).

## Interface traits keep their implementations in one file

An interface trait and its implementations live in a single file, with the
implementations in the trait's companion object — not spread across separate
files. For example, `BlobStorage` with `NoOpBlobStorage` and `S3BlobStorage` in
its companion, rather than three files. Flag a new interface trait whose
implementations are split into their own separate files.
