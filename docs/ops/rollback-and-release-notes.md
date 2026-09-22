# Rollback and release notes

Covers FR-OPS-022, FR-OPS-023 (SRS §26.3). Ticket 60.

## Rollback path (FR-OPS-022)

Every release's own notes MUST answer these three questions before it ships, because "a silently changed default is
a production incident in a single-tenant estate" (FR-OPS-023):

1. **Is the migration reversible?** Migrations in this codebase are forward-only (FR-OPS-020; see
   `db/migration/README` convention: "never edit an applied V*.sql; add V<n+1>"). A rollback therefore almost never
   means running a migration backwards - it means restoring the pre-upgrade backup (`docs/ops/backup-restore.md`)
   and redeploying the previous application version against it.
2. **What data would be lost?** Anything written by the new version's own tables or columns since the upgrade is
   lost on rollback (the restored backup predates them). List the specific tables/columns a migration adds so an
   operator can judge the blast radius before deciding to roll back mid-shift.
3. **What is the rollback procedure, concretely?**
   - Stop the new version's backend node(s).
   - Restore the pre-upgrade backup (`deploy/restore.sh`) into a database at the previous schema version, or, if
     the backup was taken *before* the migration ran, migrate no further than the previous release's own
     `V<n>__*.sql`.
   - Start the previous application version's image against it.
   - Confirm `GET /api/v1/health/dependencies` is `up` and the setup wizard's go-live checks still pass.

This is a **template**, not a substitute for a real, release-specific rollback note: each release fills in its own
answers to the three questions above (migration reversibility, data at risk, concrete steps) in that release's own
entry in the project's release notes, alongside the changed-defaults list below. A release with no schema change and
no changed default can say so in one line.

## Changed configuration defaults (FR-OPS-023)

Every release's notes MUST list, explicitly, any configuration default that changed from the previous release - not
only new configuration, but any *default value* of an existing one, since an operator who never touches that
setting still gets the new behaviour silently otherwise. Format, one line per changed default:

```
- qms.queue.call-timeout-seconds: default changed from 90 to 60 (ticket NN, reason: ...)
```

An empty section ("no configuration defaults changed in this release") is an explicit, valid entry - the point is
that it is never simply missing.
