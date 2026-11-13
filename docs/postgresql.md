# PostgreSQL storage — 2.9.0-mcexp.1

PostgreSQL is a native backend in this fork. NightCore 2.16.7 is still required; its MySQL/SQLite implementation remains available. The PostgreSQL JDBC driver 42.7.14 and HikariCP 7.0.2 are included in the plugin JAR, with no driver download at startup.

## Configuration and upgrade

The database settings remain in **engine.yml**. Startup and plugin reload add missing PostgreSQL settings without changing the selected backend, existing credentials, currency IDs, table prefix, custom messages, comments, unknown keys, empty collections, or permission tiers. Active messages remain in `lang/lang_<language>.yml`; the fork does not introduce a second messages file.

Merge the `Database.PostgreSQL` section from [the example](../examples/storage/postgresql-engine.yml) into your existing engine file, supply the real connection settings, and set `Database.Type: POSTGRESQL` when ready. On both OpenWorld and Dungeons use the **same database and table prefix** to share accounts. Keep the existing currency filenames and `Column_Name` values. Keep Vault's primary currency set to money.

Changing the backend does **not** migrate data from MySQL/SQLite. An empty PostgreSQL database will contain new accounts, not the old balances. Transfer/verify existing data separately before cutover, including UUIDs, names, each currency column, settings and hidden-from-top flags. No production database or server configuration was accessed or migrated during this implementation.

The default SSL mode is **verify-full**. Install the trusted PostgreSQL CA and use the matching hostname for that mode. Use `disable` only for an explicitly trusted local/private connection; the isolated test database uses loopback and disables TLS. Passwords are passed as datasource properties rather than embedded in JDBC URLs. Use a dedicated role/schema with only the permissions needed for account data and startup schema creation/alteration.

### Safe YAML updates

The updater validates before loading, rejects duplicate keys and non-mapping roots, preserves administrator comments/collections, checks that the file still matches its original bytes, creates private `.config-backups/*.bak`, and publishes with an atomic rename. Invalid or concurrently edited files are not overwritten. Invalid YAML is checked before reload disables the running economy. Explicit currency writes remain distinct from adding defaults, so intentional exchange-rate edits still persist.

The same protection covers engine/config, command definitions, currencies, the selected language and the leaderboard menu. Defaults inside existing administrator collections (including empty exchange rates and tax tiers) are not regenerated. No existing translations are replaced by a new default message.

## Shared account correctness

Money changes are database transactions, not delayed absolute cache snapshots. An operation locks its affected UUID rows in deterministic order, reads the current balances, applies the existing validation, limits, tax rules and cancellable events, writes the result and commits. Both sides of a transfer share that transaction. A second backend therefore cannot spend an old cached balance or overwrite a committed credit with a later metadata save.

Balance versions prevent a delayed refresh from replacing a newer cached balance. Login refreshes the account from PostgreSQL, including when the player returns to a backend with an older cache. Cached accounts refresh every second by default; that poll touches only cached UUIDs, not the entire player table. Cache display values can lag a remote commit by that interval; financial decisions always read locked database rows.

Dirty write-behind saves handle **metadata**, not money. They retain failed writes, batch up to 64 snapshots, serialize local saves, and do not hold the shared balance mutex during JDBC. Preference writes merge changed entries rather than restoring another backend's older full preference document. On shutdown, a failed metadata save is exported to a private `.recovery` file for manual reconciliation; it is not automatically replayed over shared database state.

The supported currency API and Vault endpoints use these transactions. `CoinsUser`'s managed balance-edit methods are bound to the same transaction path. Direct modification of the internal `UserBalance` map bypasses the public operation contract and must not be used by integrations. Nested financial writes or cold synchronous account loads from a balance-change event are rejected rather than deadlocking. Event handlers must not block waiting on unrelated asynchronous work.

Notifications and transaction logs are held until the database result is known. Success effects are discarded after rollback. A notification/cache-refresh error after a commit does not turn a committed payment into a reported financial failure. Financial operations are not automatically retried after an uncertain database/network result: callers should reconcile before retrying an ambiguous commit.

## Runtime bounds

- Connection pool: maximum **4**, minimum idle **0**, configurable up to 16. Budget the combined pools of both backends and other plugins.
- Account I/O workers: **2 daemon threads**, **128 queued jobs**, with idle thread timeout and failed-future backpressure.
- Cold lookups are coalesced; cached account access does not wait for an unrelated SQL lookup.
- UUID primary key; expression index on `lower(name)` for case-insensitive lookup. SQL identifiers are validated and quoted, including reserved account fields.
- Prepared queries are rebuilt only when currencies change. Each connection caches up to 64 prepared queries / 1 MiB. Resources, arrays, statements and connections are closed.
- Query timeout defaults to **5 seconds**, pool wait **3000 ms**, with bounded driver/socket settings. Batch execution also has a JDBC query timeout.
- Leaderboards use a streamed, ordered projection of UUID/name/balance instead of loading full account/preference objects for ranking. The existing complete leaderboard itself still grows with account count.

Normal player commands and asynchronous APIs perform PostgreSQL work away from the game thread. **Synchronous Vault APIs can still block their calling thread for the database round trip**, especially during a database outage or lock wait; this cannot be made asynchronous without changing their return contract. Prefer the asynchronous ExcellentEconomy API for integrations, keep PostgreSQL close to both backends, and size timeouts/pools for the actual deployment. No zero-lag or fixed heap guarantee is made.

### Storage-internals compatibility

The public currency/Vault APIs remain. `DataHandler` is now a storage facade rather than a subclass of NightCore's MySQL/SQLite database manager. Existing basic legacy schema/config getters are delegated for MySQL/SQLite. Integrations that cast it to `AbstractDatabaseManager` or issue raw NightCore-dialect SQL must change; use the supported currency API or account accessor. Raw NightCore SQL/schema operations are deliberately unavailable for PostgreSQL.

## Verification

See [PostgreSQL verification](postgresql-verification.md). No production deployment, automatic balance migration or GitHub push is included in this change.
