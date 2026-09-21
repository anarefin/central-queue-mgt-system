# BI access (FR-RPT-023, ticket 53)

A client's own BI tool can read the reporting warehouse directly, without ever touching a live transactional table
or the application's own database user.

## The stable view layer

Migration `V44__retention_and_bi_access.sql` creates a `bi` schema holding one versioned view per reporting fact
table: `bi.ticket_fact_v1` today. A later minor release that needs to change a column adds `bi.ticket_fact_v2`
rather than changing `_v1` underneath an existing warehouse job — the view layer named in a client's own BI tool
stays stable across minor releases, the guarantee FR-RPT-023 asks for.

`bi.ticket_fact_v1` deliberately leaves out direct visitor identifiers (`visitor_id`, `visitor_code`,
`visitor_name`); `visitor_category` alone is enough for a BI cross-tab. It answers over both still-detailed and
already-anonymised rows (`reporting.ticket_fact.anonymized_at`), so a client's warehouse keeps counting a Ticket
the same way across the operational retention purge.

## Provisioning a client's own read-only user

The same migration creates a `NOLOGIN` group role, `qms_bi_reader`, granted `USAGE` on the `bi` schema and `SELECT`
on every view in it (including one a later migration adds, via `ALTER DEFAULT PRIVILEGES`). It carries no login of
its own, so no password ever needs to live in a migration or in source control.

To provision a client's BI tool, a consultant creates one `LOGIN` role for them and grants it membership in
`qms_bi_reader`:

```sql
CREATE ROLE bi_client LOGIN PASSWORD 'choose-a-strong-password' IN ROLE qms_bi_reader;
```

Point the client's BI tool at the database (`qms`) with that role's own credentials. It can `SELECT` from anything
under `bi.*` and nothing else — not the live transactional tables, not `reporting.ticket_fact` directly, not
`audit_log`.

To revoke access later: `DROP ROLE bi_client;` (or `REVOKE qms_bi_reader FROM bi_client;` to keep the login but take
away the read access).

## The nightly extract

For a client who runs their own warehouse rather than connecting live, `ReportingExtractRunner` (ticket 53,
FR-INT-060) writes a nightly CSV extract of `bi.ticket_fact_v1` to a configured directory
(`qms.reporting.extract.location`, default `./data/reporting-extracts`; `QMS_REPORTING_EXTRACT_LOCATION` in
`deploy/compose.yaml`), one `ticket_fact-<yyyyMMdd>.csv` file per night. It reads the exact same view a live BI
connection would, so the two paths can never drift from one another.
