# PostgreSQL verification — 2.9.0-mcexp.1

Verification completed locally on 12–13 November 2026. Starting revision `fdc39e7`. No production server, account, balance or database was changed. No GitHub push was performed.

## Automated checks

- **49 ordinary/component tests and 9 real PostgreSQL tests** pass, zero failures. `test postgresTest jacocoTestReport build` succeeds with Java 25 / Gradle 9.4.1 and the existing default Paper 26.3 API.
- PostgreSQL **18.6**, official Docker image, isolated loopback-only database with ephemeral credentials. Test prefixes are unique and removed after each test. No production credentials are used.
- Two independently configured connection pools access the same account: twenty concurrent withdrawals from a balance of 100 acknowledge exactly ten withdrawals of 10; final stored balance zero. A stale metadata save cannot restore the old balance.
- Native Vault Unlocked transfers exercise the real currency manager against PostgreSQL with deliberately stale cached account references. The second insufficient-fund transfer fails and both database balances remain conserved.
- Cancelled balance events roll back the database. A failed post-commit notification cannot turn an acknowledged commit into a reported money failure. Rollback suppresses success effects and cached/unselected transaction objects cannot be edited.
- Account creation is idempotent and cannot overwrite an existing balance. Missing-row metadata batches roll back; deleted accounts are not resurrected. Names are queried case-insensitively and both UUID and name indexes are checked.
- Dirty metadata survives failed saves and expiry, edits during I/O remain pending, snapshots are immutable, and JDBC does not run under the shared balance mutex. A slow cold query does not block a cached account; cold concurrent requests share a result.
- Maintenance invalidates stale in-flight loads. Online UUID resolution wins over a stale/reused cached name. Unloaded player entrypoints do not issue synchronous SQL under the financial lock.
- YAML tests cover real disk updates, idempotence, custom values/comments, unknown keys, empty lists/maps, omitted administrator collection entries, explicit collection edits, private backups, invalid roots/duplicates/syntax, concurrent file edits and validation before disabling a running economy on reload.
- PostgreSQL tests also cover profile reads, metadata/preferences, cache synchronization, streamed leaderboard projection, purge, deletion by name, empty batches, invalid identifiers/reserved columns and starting values.

RED checkpoints are `239c5fc` (new storage/configuration contracts), `4cfb3bd` (a real cached-read timeout under the former global repository lookup lock), and `14fa769` (cross-backend atomic balance contract). The implementation is in `4f7d49b`; subsequent commits add release checks and tests. Initial test-fixture setup errors are not treated as business-logic RED evidence.

## Paper runtime checks

An isolated **Paper 26.3 build 248** server used NightCore **2.16.7**, assembled from its published Maven modules using the upstream distribution's own module-shading set. Dependency code was not changed. This is not a production deployment or a real-player/client test.

The current plugin was loaded, exposed its service API, created two test accounts, deposited/withdrew money and atomically transferred between them. A later server restart verified stored balances **1000 / 1030**. Plugin reload restored a deliberately removed PostgreSQL setting on disk and retained administrator comments. Shutdown closed the connection pools. A malformed configuration reload was rejected without overwriting the malformed document; the repaired configuration was reloadable.

A separate startup pointed PostgreSQL at an unreachable loopback port. ExcellentEconomy was disabled, kept `Database.Type: POSTGRESQL`, and created no fallback economy SQLite database. NightCore's own independent SQLite database is unrelated. A legacy **SQLite** startup/operations/reload/shutdown smoke also passed, retaining the previous backend path.

Raw temporary smoke evidence is kept outside the repository under `/tmp/excellenteconomy-paper-test/`. Delivery copies exclude credentials and raw private configurations. The repeated server checks use controlled API calls and do not certify every external integration, Folia region ownership in live play, or every Minecraft build.

## Runtime measurements

One loopback PostgreSQL run, warm pool, local Mac/Colima host:

| Probe | Result |
| --- | --- |
| Case-insensitive lookup with 30,000 seeded accounts | p50 **0.348 ms**, p95 **0.416 ms** |
| Durable one-account atomic monetary write | p50 **1.150 ms**, p95 **1.371 ms** |

The name probe also asserts that PostgreSQL uses the expression index. These are observations from one local run, not production SLOs. Hardware, network, TLS, contention and other plugins affect runtime. No comparative whole-server lag or absolute heap guarantee is claimed. Structural bounds are documented in the storage guide.

## Review and limits

A separate local read-only database review identified and prompted fixes for lock ordering, stale cache installation after maintenance, cache pinning, synchronization/save ordering, shared monetary authority, event reentry, transaction-owned objects, deferred effects, prefix validation and shutdown handling. Its output was reviewed locally; it is not a CodeRabbit review. CodeRabbit CLI 0.9.5 was available but reported `authenticated: false`; no remote code review or code upload was run.

The PostgreSQL backend uses database-authoritative balance changes, while cache polling can lag remote display updates by the configured interval. Synchronous Vault methods still wait for their SQL result; use async APIs where possible. An uncertain connection failure at commit cannot safely be retried automatically without caller-provided idempotency. No automated MySQL/SQLite-to-PostgreSQL migration is included. Advanced consumers of NightCore-specific DataHandler superclass/SQL internals require adaptation; the public currency/Vault APIs are retained.
