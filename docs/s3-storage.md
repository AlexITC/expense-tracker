# S3 Blob Storage

Documents (receipts, invoices) are stored in AWS S3. The `BlobStorage` trait is implemented by `S3BlobStorage`.

## Required environment variables

| Variable | Description |
|---|---|
| `AWS_REGION` | AWS region (default: `us-west-2`) |
| `AWS_ACCESS_KEY_ID` | AWS access key ID |
| `AWS_SECRET_ACCESS_KEY` | AWS secret access key |
| `AWS_S3_BUCKET` | S3 bucket name (default: `replace-me-bucket`) |
| `AWS_S3_ENDPOINT_OVERRIDE` | *(optional)* Override the S3 endpoint URL — set for localstack tests, unset in prod/dev |

## Operating modes

**Production**
```
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_S3_BUCKET=my-prod-documents
```

**Local dev** (real AWS, separate dev bucket)
```
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_S3_BUCKET=my-dev-documents
```

**Tests** (localstack — started automatically by the test suite)
```
AWS_S3_ENDPOINT_OVERRIDE=http://localhost:4566
AWS_S3_BUCKET=test-documents
# Credentials can be anything; localstack doesn't validate them
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
```

## Bucket management

Prod and dev buckets are created manually out of band. The application never attempts to create them.

The localstack test bucket (`test-documents`) is created by the test suite in `beforeAll` and cleaned between tests.

## Storage key format

```
data/<user_id>/<upload_date YYYY-MM-DD>/<sha256>
```

One bucket serves all users; users are namespaced by the `user_id` prefix in the key.

## Upload ordering and failure safety

1. Compute sha256; check per-user dedup in the DB — abort if duplicate (no S3 call).
2. PUT bytes to S3 synchronously.
3. Create the document row and background-job row atomically in the DB.

If step 2 fails, no DB row is created — the upload fails cleanly.
If step 3 fails after a successful S3 put, a retry is safe: the dedup check finds no row, the S3 PUT overwrites idempotently, and the DB insert succeeds on the retry.

## Delete ordering

1. Fetch the document row to retrieve its `storage_key`.
2. Delete the S3 object (`delete` is idempotent — a missing object is treated as success).
3. Delete the document row.

Deleting an expense only nulls `document.expense_id` (ON DELETE SET NULL) and does not touch S3.
