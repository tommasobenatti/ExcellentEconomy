# Verification — 2.8.1-mcexp.1

Date: **2026-10-13**. Starting revision: **5abcf03**, ExcellentEconomy 2.8.0. Scope was derived from the user's request and upstream references; no separate plan file was supplied.

## Result

**35 tests passed, zero failures/errors** on the current implementation. Build and Java compilation pass against Paper API **26.1.2.build.74-stable**, **26.2.build.133-stable**, and **26.3.build.178-stable** using Java 25 and Gradle 9.4.1. These are compilation and component tests with mocked Bukkit/persistence, not live Minecraft-server tests.

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

## RED/GREEN evidence

TDD commits are preserved on the current branch. Missing-implementation compile failures were used for the transaction, tax, native-provider, scheduler, and maintenance contracts, followed by the implementations and passing tests. Initial mocked-server/configuration setup failures were repaired as fixture issues and are not counted as business-logic RED evidence.

Runtime reproductions include:

- The optional factory verifier failed with `NoClassDefFoundError: net/milkbowl/vault/economy/Economy` before isolation; it now passes with Vault classes hidden.
- The legacy row mapper failed the zero-floor assertion because it imported `-100` as `+100`; the corrected mapper returns zero.
- A receiver cancellation could refund a successfully reported nested withdrawal. The reproducer failed before the event mutation guard and now passes.
- Invalid tax text did not raise an error; invalid/missing bracket numbers now fail validation.
- Native withdrawals were incorrectly denied when an existing balance exceeded a newly lowered cap; reducing such a balance now succeeds.
- A 33-character account name passed validation against a 32-character column; the boundary test now passes.

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
| Whole plugin | **794/2599 (30.6%)** | **219/700 (31.3%)** |

The coverage target is met for the new transaction/tax core. Whole-project coverage remains below 80%; much of the existing GUI, migrations, database infrastructure, placeholders, lifecycle, and native API compatibility boilerplate is not exercised by this suite. No whole-plugin coverage claim is made.

Generated reports: `build/reports/tests/test/index.html`, `build/reports/jacoco/test/html/index.html`, and `build/reports/jacoco/test/jacocoTestReport.xml`.

## Remaining limits

- No live Paper/Folia server, connected clients in different regions, or real SQLite/MySQL integration test was run. The database row mapper and persistence-facing account logic were tested with mocks.
- PaperMC's download API listed Folia through **26.2**, with no official **26.3** build on the verification date. Folia 26.3/fork behavior requires a matching runtime for validation.
- Published NightCore **2.16.7** source (`AbstractMenuBase.handleClick`) calls the global `plugin.runTask` for inventory click actions. This fork deliberately uses the existing text leaderboard on Folia rather than registering that GUI.
- The balance lock is local to one server process. Existing NightCore caching and cross-server synchronization are retained, with no distributed transaction guarantee.
- Shared/bank accounts, arbitrary-precision storage, and native `AsyncEconomy` are not advertised. UUID personal accounts, the synchronous native provider, and the existing asynchronous ExcellentEconomy API are supported.
- No upstream issue/PR was modified, no pull request was opened, and no commit was pushed.
