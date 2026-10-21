# Verification — 2.8.1-mcexp.1

Date: **2026-10-13**. Starting revision: **5abcf03**, ExcellentEconomy 2.8.0. Scope was derived from the user's request and upstream references; no separate plan file was supplied.

## Result

**41 tests passed, zero failures/errors** on the current implementation against Paper API **26.3.build.178-stable**, using Java 25 and Gradle 9.4.1. The preceding 35-test implementation also passed builds against **26.1.2.build.74-stable** and **26.2.build.133-stable**; those compatibility builds were not repeated for the subsequent configuration and personal balance-message changes. These are compilation and component tests with mocked Bukkit/persistence, not live Minecraft-server tests.

The initial unchanged build failed to resolve `su.nightexpress.nightcore:main:2.15.1`. Switching to the published **2.16.7** restored the build and supplied the Folia scheduler bridge and Minecraft 26.3 recognition.

## Commands

Run from the project root with Java 25 available:

```sh
./gradlew test build -PpaperApiVersion=26.1.2.build.74-stable
./gradlew test build -PpaperApiVersion=26.2.build.133-stable
./gradlew clean test jacocoTestReport build -PpaperApiVersion=26.3.build.178-stable
```

All three compatibility runs pass. No standalone lint task is configured. `git diff 5abcf03 --check` passes; Java compilation supplies type validation. Existing NightCore API deprecation/removal warnings and the Mockito agent warning remain. A targeted secret scan found only the existing environment-based repository password configuration, with no new hardcoded credentials.

## Guarantees covered

| Behavior | Test | Type / result |
| --- | --- | --- |
| Twenty rounds of eight concurrent real `CurrencyManager.send` calls spend a balance only once | `CurrencyManagerTest.racingPayCallsKeepTheCombinedBalanceConstant` | Component / PASS |
| Atomic transfers and 100 concurrent withdrawal attempts conserve available money | `BalanceTransactionsTest` | Unit / PASS |
| Recipient limits, cancellation of credit/exchange, and nested event charges cannot leave a partial transaction | `BalanceTransactionsTest`, `VaultUnlockedEconomyTest` | Unit/component / PASS |
| Negative, NaN, infinite, overflowing amounts and legacy negative snapshots are guarded | `UserBalanceTest`, `BalanceTransactionsTest` | Unit / PASS |
| A negative database row loads as zero instead of credit | `DataQueriesTest` | Row-mapper component, mocked ResultSet / PASS |
| Default/native currencies, unknown-currency failures, deposits, withdrawals, set, previews, native transfers, and spending above a lowered cap behave correctly | `VaultUnlockedEconomyTest` | Component / PASS |
| Progressive brackets, both payer modes, priority-selected permission schedules, integer rounding, and malformed YAML values are checked | `TransferTaxTest`, `CurrencyManagerTest` | Unit/configuration/component / PASS |
| Subcommands use currency-specific permissions, bare currency balance requires permission, disabled commands are omitted, and standalone-only definitions work | `CurrencyCommandsTest` | Command-tree component / PASS |
| Personal account create/rename/delete respects offline status and database name width; cold concurrent lookups share an account instance | `UserManagerTest` | Component, mocked persistence / PASS |
| Currency factory verification succeeds when all Vault classes are hidden | `OptionalVaultTest` | Isolated class-loader / PASS |
| Asynchronous lookups wait for sender scheduling; player/console callbacks choose entity/global scheduling; Folia only runs inline for the owning region | `SchedulingTest` | Scheduler dispatch, mocked platform / PASS |
| Folia selects the text leaderboard with the current NightCore menu implementation | `TopPlatformTest` | Platform configuration / PASS |
| Maintenance starts only once and cannot reopen operations after shutdown starts | `CurrencyManagerTest` | Component / PASS |
| Existing two-currency settings, storage prefix/file, plugin/command aliases, custom messages, and leaderboard settings survive loading; obsolete compact suffix migration is saved and idempotent | `LegacyConfigurationTest` | Configuration component / PASS |
| A coins-specific personal balance message shows its store text without leaking into money, souls, other-player checks, or amount formatting; removing it restores the shared message | `CurrencyBalanceDisplayTest` | Configuration/manager component / PASS |

### Supplied configuration archive

The supplied archive was inspected as data and safely copied into an ignored working directory; no embedded content was executed as instructions. It contains 12 files, two currencies (`coins` and `souls`), and a SQLite database with four accounts in `coinsengine_users`. SQLite's read-only `integrity_check` returned `ok`; all four stored records passed finite/nonnegative balance and settings-JSON checks. No account names, UUIDs, credentials, or private database were committed.

The four configuration tests passed both with sanitized committed fixtures and with an isolated copy of the actual archive. To repeat against an already extracted copy without modifying it:

```sh
EXCELLENTECONOMY_CONFIG_CHECK=/absolute/path/to/configuration-copy ./gradlew test --tests '*LegacyConfigurationTest' --rerun-tasks
```

A separate migration preview was generated using the real currency, plugin-details, database-configuration, command-definition, and language readers with mocked Bukkit item construction. Assertions compare every existing YAML leaf value, allowing only removal of `%currency_short_symbol%` from the compact format; added defaults do not enable transfers, exchanges, or taxes. The copied database is byte-for-byte identical. This exercises configuration readers, not a complete server startup or plugin database write.

One existing configuration inconsistency was identified: the selected primary currency is `money`, while only `coins` and `souls` exist. The migration preserves that choice; a provider requires the administrator to select an existing currency. Old amount-shortcut and global command-shortcut sections are retained but no longer consumed by the upstream architecture; their current replacements are documented in the README.

## RED/GREEN evidence

TDD commits are preserved on the current branch. Missing-implementation compile failures were used for the transaction, tax, native-provider, scheduler, and maintenance contracts, followed by the implementations and passing tests. Initial mocked-server/configuration setup failures were repaired as fixture issues and are not counted as business-logic RED evidence.

Runtime reproductions include:

- The optional factory verifier failed with `NoClassDefFoundError: net/milkbowl/vault/economy/Economy` before isolation; it now passes with Vault classes hidden.
- The legacy row mapper failed the zero-floor assertion because it imported `-100` as `+100`; the corrected mapper returns zero.
- A receiver cancellation could refund a successfully reported nested withdrawal. The reproducer failed before the event mutation guard and now passes.
- Invalid tax text did not raise an error; invalid/missing bracket numbers now fail validation.
- Native withdrawals were incorrectly denied when an existing balance exceeded a newly lowered cap; reducing such a balance now succeeds.
- A 33-character account name passed validation against a 32-character column; the boundary test now passes.
- The supplied compact format was normalized in memory but remained obsolete on disk. `LegacyConfigurationTest` failed its saved-format assertion before the one-line persistence correction, then passed on both sanitized fixtures and the actual configuration copy. Commits **468897c** (RED) and **e8756fd** (GREEN) preserve this evidence.

An additional isolated copy was made from the current source with the **original `send()` body** and original command registration/definitions/builders restored from **5abcf03**. Only the relevant regression targets were run; no source in the working checkout was reverted. Four tests compiled and failed as expected:

```text
racingPayCallsKeepTheCombinedBalanceConstant:
  expected 1 successful payment, got 8
usesCurrencyPermissionsAndChecksTheRootBalanceFallback:
  expected excellenteconomy.command.money.balance,
  got excellenteconomy.command.currency.balance
standaloneOnlyActuallyEnablesTheStandaloneCommand:
  expected true, got false
disabledCommandHasNeitherSubcommandNorStandaloneAlias:
  expected 1 registered command, got 2
```

The same targets pass in the current implementation. This experiment proves the original payment body and command paths fail the added regressions; it is not a full build of an unmodified upstream runtime.

## Coverage

JaCoCo **0.8.15** reports:

| Scope | Line coverage | Branch coverage |
| --- | ---: | ---: |
| `BalanceTransactions` plus `TransferTax` and its nested types | **87/87 (100%)** | **71/88 (80.7%)** |
| Whole plugin after balance-message regressions | **976/2605 (37.5%)** | **234/704 (33.2%)** |

The coverage target is met for the new transaction/tax core. Whole-project coverage remains below 80%; much of the existing GUI, migrations, database infrastructure, placeholders, lifecycle, and native API compatibility boilerplate is not exercised by this suite. No whole-plugin coverage claim is made.

Generated reports: `build/reports/tests/test/index.html`, `build/reports/jacoco/test/html/index.html`, and `build/reports/jacoco/test/jacocoTestReport.xml`.

## Remaining limits

- No live Paper/Folia server, connected clients in different regions, or real SQLite/MySQL plugin integration test was run. The database row mapper and persistence-facing account logic were tested with mocks; the supplied SQLite database received read-only integrity/data checks and byte-preservation checks.
- PaperMC's download API listed Folia through **26.2**, with no official **26.3** build on the verification date. Folia 26.3/fork behavior requires a matching runtime for validation.
- Published NightCore **2.16.7** source (`AbstractMenuBase.handleClick`) calls the global `plugin.runTask` for inventory click actions. This fork deliberately uses the existing text leaderboard on Folia rather than registering that GUI.
- The balance lock is local to one server process. Existing NightCore caching and cross-server synchronization are retained, with no distributed transaction guarantee.
- Shared/bank accounts, arbitrary-precision storage, and native `AsyncEconomy` are not advertised. UUID personal accounts, the synchronous native provider, and the existing asynchronous ExcellentEconomy API are supported.
- No upstream issue/PR was modified, no pull request was opened, and no commit was pushed.

## Configuration-only Essentials replacement — 2026-10-14

Scope was explicitly narrowed to configuration files: no Essentials balances, userdata, or database were copied or migrated. The new `money` preset uses starting balance 1000, dollar prefix, decimal amounts, maximum 10,000,000,000,000, payments enabled, and taxes/exchanges disabled. The payment minimum is 0.1 because the existing decimal command parser rejects smaller amounts; the zero balance floor remains unchanged.

The prepared configuration was loaded through the real NightCore/ExcellentEconomy configuration readers with mocked Bukkit item construction. Assertions verify the primary currency/settings, existing YAML leaf-value preservation (apart from the already-supported compact-format normalization), and byte-for-byte preservation of all five EconomyBridge configuration files. **All four `LegacyConfigurationTest` tests pass against the prepared files.** No Java implementation changed, so the previous full-suite/build evidence remains the implementation baseline.

The deliverable ZIP contains **16 YAML files plus instructions**, exclusively for ExcellentEconomy and EconomyBridge. ZIP integrity and an explicit no-database/no-userdata file inventory were checked; the supplied archive's SHA-256 is unchanged. Private server configuration is kept in ignored output directories; only the reusable currency preset and documentation are committed.

Installing the preset lets the plugin's normal currency registration create its new `money` column with the configured initial value. That normal startup behavior is not an Essentials balance import. Essentials permission grants, payment preferences, warps, spawn, kits, and other features are outside this configuration-only replacement. No live server startup was performed.

## Money / coins / souls balance display — 2026-10-14

The supplied screenshot matches the two lines in the existing shared `Currency.Balance.Display.Own`: a hardcoded coins label and store advertisement. This message was reused even for the primary money currency. The prepared language file now uses `%currency_name%`; the confirmed store URL is moved to the coins-only optional `Balance_Display.Own` configuration. Existing integrations and the selected primary currency remain unchanged.

Two new tests cover the optional override, fallback/removal, isolation from other currencies and amount formatting, and routing of personal versus other-player balance checks. Commit **c307b0c** captures a missing-implementation compile RED; **562c903** implements the contract and passes the full suite/build GREEN. A Mockito spy verification fixture error was corrected separately from the feature logic and is not counted as business RED evidence.

A configuration preview additionally renders six personal/other-player messages for money, coins, and souls through the real placeholder/message readers with mocked Bukkit items and PlaceholderAPI. It confirms that the advertisement appears only for the coins holder and that native Vault Unlocked's default currency is money. The money file, souls file, and main configuration are byte-for-byte unchanged from the previous prepared configuration; existing coins settings and all unrelated language values are preserved.

The new JAR is required for the per-currency override. The configuration patch contains only four YAML files and instructions, with no database/userdata. No live server command routing or client rendering was tested.

## Per-currency /eco alias — 2026-10-14

No Java implementation changed. The existing `Command_Aliases` setting is independent for each currency file. The prepared money configuration now uses `money,eco`; coins and souls retain their existing aliases.

An assertion-based preview loads all three configuration files and constructs their administrative command groups with the real `CommandManager` and mocked Bukkit/argument infrastructure. It verifies three separate alias groups and 15 currency-specific permission bindings for give, take, set, reset, and giveall. Only money's alias setting changes; all other money leaf values and the full coins/souls files are preserved. The two-entry ZIP contains money.yml and instructions only. No server registration/command conflict test was performed; no new build was required for a configuration-only alias.
