# ExcellentEconomy — MCExp fork

**Everything that sould've been done...**

A focused fork of [NightExpress's ExcellentEconomy](https://github.com/nulli0n/ExcellentEconomy), based on **2.8.0**, with native **Vault Unlocked**, per-currency command permissions, progressive transfer taxes, and **Folia-aware scheduling**. The plugin name, existing currency files, SQLite/MySQL storage, and public ExcellentEconomy API are retained.

Current fork version: **2.8.1-mcexp.1**.

## What changed

- **Native Vault Unlocked API 2.20:** UUID accounts, all registered currencies, balance queries, deposits, withdrawals, balance setting, transaction previews, and atomic account-to-account transfers. Legacy Vault remains available separately.
- **Safe payments:** balance checks and both sides of a transfer share a transaction lock. Repeated `/pay` calls cannot spend the same balance several times. Cancelled balance events roll back both sides, including currency exchanges.
- **Strict amounts:** operations reject negative, NaN, infinite, overflowing, and insufficient-fund requests. Deposits and payments respect the recipient's balance limit; withdrawals can reduce an existing balance above a newly lowered limit.
- **Granular command access:** per-currency permissions cover subcommands, primary-currency standalone commands, and the root balance shortcut. Denied child commands are filtered through NightCore's command suggestions.
- **Per-currency command switches:** disable individual command IDs in the currency file without changing the global command definitions.
- **Progressive transfer taxes:** independent schedules per currency, permission-based replacement schedules, an explicit payer choice, and tax exemptions.
- **Folia:** asynchronous account lookups return to the sender's entity scheduler; player notifications use their recipient's scheduler. Shared balances, settings, registries, raw formatting, and maintenance admission are protected for concurrent use.
- **Safer startup and shutdown:** optional Vault classes are isolated, a primary currency exists without Vault, native services are unregistered on reload/disable, and background maintenance cannot reopen a closing economy.
- **Legacy data:** persisted negative balances load as zero instead of becoming positive money.
- **Configuration upgrades:** legacy compact currency formats are normalized and saved; existing storage names, currency settings, aliases, and custom messages are retained.

Existing features remain: custom currencies and formatting, wallet, exchange rates, PlaceholderAPI and legacy CoinsEngine placeholders, transaction logs, migrations, initial/max balances, and leaderboards.

## Requirements and compatibility

- **Java 25+**; builds use the Java 25 toolchain.
- **[NightCore 2.16.7](https://github.com/nulli0n/nightcore-spigot)** or a compatible newer release. The original build referenced 2.15.1, which is no longer available from its configured repository.
- Optional: **[Vault Unlocked](https://github.com/TheNewEconomy/VaultUnlocked)** with **VaultUnlockedAPI 2.20+**, or classic Vault for its legacy API; PlaceholderAPI; PlayerPoints for migration.

| Platform / Minecraft | Status of this fork |
| --- | --- |
| Paper **26.3** | Compiled and tested against `26.3.build.178-stable`; targets 26.3 builds exposing the compatible Paper API. |
| Paper **26.2** | Compiled and tested against `26.2.build.133-stable`. |
| Paper **26.1.2** | Compiled and tested against `26.1.2.build.74-stable`. |
| Folia **26.2 / 26.1.2 / 1.21.11 / 1.21.8** | Entity/global scheduling support is implemented; no live-server certification was performed. Leaderboards use chat. |
| Folia **26.3** and compatible forks | Code targets the shared Paper/Folia scheduling APIs. An official Folia 26.3 build was not available in PaperMC's download API on **2026-10-13**; live verification remains outstanding. |
| Existing older Paper/Spigot targets: **1.21.8–1.21.11, 26.1.1** | Original API range and `api-version: 1.21` retained; these runtimes were not independently tested. |

Compilation and mocked API tests do **not** certify every server build or third-party plugin. Optional integrations must also support the chosen server platform.

**Folia leaderboard limitation:** NightCore 2.16.7 schedules inventory-menu click actions on the global scheduler. This fork uses the existing text leaderboard on Folia even when `Top.Use_GUI` is enabled, and logs the reason. Paper keeps the inventory menu.

## Installation and upgrade

1. Build the plugin or obtain the fork's JAR.
2. Install NightCore and the chosen optional integrations. Vault Unlocked normally registers under the plugin name `Vault`; install it as the replacement for classic Vault.
3. Back up the existing ExcellentEconomy folder and database, replace the plugin JAR, and restart the server.
4. Keep existing currency IDs and database column names. New settings default to the existing command behavior and **taxes disabled**.
5. Configure permissions and taxes, then restart to apply command/primary-currency changes consistently.

Existing storage is retained; no new database schema is introduced. The legacy zero balance floor remains mandatory. The separate [CoinsEngine upgrade guide](https://nightexpressdev.com/excellenteconomy/upgrade-guide/) applies to switching from CoinsEngine.

### Keep an existing configuration and database

Copy the **whole existing data folder** into `plugins/ExcellentEconomy` while the server is stopped, including `engine.yml`, `config.yml`, `commands.yml`, `currencies/`, `lang/`, `menu/`, and the database named in `Database.SQLite.FileName`. If changing from CoinsEngine, remove its JAR before starting ExcellentEconomy. Never run both plugins against the same database.

Keep `Database.Table_Prefix` unchanged, including a legacy value such as `coinsengine`; regenerating `engine.yml` with the new default prefix would select a different user table. Keep currency filenames/IDs and `Column_Name` values unchanged. Existing root aliases such as `coinsengine,coe` remain valid through `Plugin.Command_Aliases`.

The supplied two-currency configuration (`coins` and `souls`) is covered by regression tests: integer balances, transfer/exchange restrictions, synchronization, initial/max values, aliases, custom notifications, and leaderboard settings survive loading. New command switches enable the existing commands and transfer taxes start **disabled**. The obsolete `%currency_short_symbol%` is removed from `Format_Short` and the normalized value is saved; compact `%amount%` already includes its suffix. Repeated loading produces the same result.

Existing `Children.Aliases`/`Dedicated` sections are read by the command-definition compatibility reader. Current definitions use `Children.Alias`/`Standalone`; current `give`, `giveall`, and `send` entries take effect when stale `add`, `addall`, and `pay` entries also exist.

**Check the primary currency:** `Integration.Vault.EconomyCurrency` must name a currency file that actually exists. A value of `money` with only `coins.yml` and `souls.yml` does not register an economy provider. The upgrade retains this setting until an administrator chooses the intended currency; it does not create or rename currencies to guess that choice.

Older `General.Amount_Shortcuts` and `Economy.Command_Shortcuts` sections remain in the file for preservation but are not read by the current upstream architecture. Amount shortcuts come from NightCore's `Number.Shortcut_List`; the supplied `k`, `m`, and `b` values match its defaults. Standalone command switches come from `commands.yml`. This check does not change another plugin's NightCore configuration.

## Vault and Vault Unlocked

In `config.yml`:

```yaml
Integration:
  Vault:
    Enabled: true
    EconomyCurrency: money
  VaultUnlocked:
    Enabled: true
```

`Integration.Vault.EconomyCurrency` selects the primary/default currency and its standalone commands, including `/pay`, even when classic Vault integration is disabled or Vault is absent. Native Vault Unlocked exposes **all** registered currencies; legacy Vault exposes the primary currency.

The APIs are provided by the optional plugin, not bundled in this JAR. Missing Vault APIs do not prevent ordinary currencies from loading. Older Vault Unlocked releases can still provide the legacy Vault API; the native provider requires API 2.20+.

Native account support is for personal UUID/player accounts. Account creation is idempotent; rename/delete require an existing offline account, and names must fit the existing 32-character database column. Shared/bank accounts are not supported, and their API methods report failure. World parameters use the existing global account balances. The native provider is synchronous; the existing ExcellentEconomy API continues to offer asynchronous methods.

`BigDecimal` API requests are checked and translated to the plugin's existing **double-backed** balances. This is not an arbitrary-precision storage migration. Whole-number currencies round operation amounts down and return the normalized amount in their response.

## Currency commands and permissions

Currency settings live in `plugins/ExcellentEconomy/currencies/<id>.yml`. For example:

```yaml
Command_Aliases: money,cash
Permission_Required: true
Commands:
  Disabled: giveall,exchange
Transfer_Allowed: true
Transfer_Min_Amount: 1.0
Start_Value: 0.0
Max_Value: -1.0
```

`Commands.Disabled` is a **comma-separated string of command IDs**, using NightCore's existing array format. An empty string enables all available commands. IDs are independent of aliases: disable `send` to remove `pay`, or `remove` to remove `take`. A disabled balance command also disables the root balance fallback. Existing settings for transfer, exchange, and leaderboard availability still apply.

Global aliases and child/standalone switches remain in `commands.yml`. Standalone currency commands belong to the configured primary currency.

For currency `money`:

| Operation | Default child command | Permission |
| --- | --- | --- |
| Currency access, when required | `/money` | `excellenteconomy.currency.money` |
| Own balance / root shortcut | `/money balance`, `/money` | `excellenteconomy.command.money.balance` |
| Other player's balance | `/money balance <player>` | Also `excellenteconomy.command.money.balance.others` |
| Payment, ID `send` | `/money pay <player> <amount>` | `excellenteconomy.command.money.send` |
| Give, ID `give` | `/money give <player> <amount>` | `excellenteconomy.command.money.add` |
| Give all, ID `giveall` | `/money giveall <amount>` | `excellenteconomy.command.money.addall` |
| Remove, ID `remove` | `/money take <player> <amount>` | `excellenteconomy.command.money.take` |
| Set | `/money set <player> <amount>` | `excellenteconomy.command.money.set` |
| Reset | `/money reset <player>` | `excellenteconomy.command.money.reset` |
| Payment preference | `/money payments` | `excellenteconomy.command.money.payments` |
| Other player's preference | `/money payments <player>` | Also `excellenteconomy.command.money.payments.others` |
| Exchange | `/money exchange <currency> <amount>` | `excellenteconomy.command.money.exchange` |
| Leaderboard | `/money top` | `excellenteconomy.command.money.top` |

Replace `money` with the currency ID. The `add`, `addall`, and `take` permission suffixes preserve the original permission vocabulary.

Existing global nodes such as `excellenteconomy.command.currency.send` remain registered as parents of their corresponding per-currency nodes. Existing grants therefore continue to apply; exact per-currency grants/denials permit narrower rules. Currency reset inherits the existing `excellenteconomy.command.reset` node. Operator/global command defaults remain unchanged.

A payment-only player can receive currency access and `excellenteconomy.command.money.send` while balance/admin nodes are denied. `/money` then reports the denied balance permission instead of showing the balance. Primary standalone aliases perform the same currency-access and command checks. Exchanges also check access to the destination currency.

Plugin administration and wallet permissions retain their existing global nodes, including `excellenteconomy.command.reload`, `.create`, `.resetall`, `.migrate`, `.wallet`, and `.wallet.others`.

## Progressive transfer taxes

Add this section to each currency file:

```yaml
Transfer_Tax:
  Enabled: true
  Payer: SENDER # SENDER or RECEIVER
  Brackets:
    tax_free:
      Threshold: 0.0
      Percent: 0.0
    standard:
      Threshold: 100.0
      Percent: 5.0
    large:
      Threshold: 1000.0
      Percent: 10.0
  Permission_Tiers:
    vip:
      Permission: excellenteconomy.tax.money.vip
      Priority: 10
      Brackets:
        tax_free:
          Threshold: 0.0
          Percent: 0.0
        standard:
          Threshold: 100.0
          Percent: 2.0
        large:
          Threshold: 1000.0
          Percent: 5.0
    exempt_rank:
      Permission: excellenteconomy.tax.money.exempt_rank
      Priority: 20
      Brackets:
        all:
          Threshold: 0.0
          Percent: 0.0
```

Each rate applies only to the portion above its threshold, up to the next threshold. For a transfer of **2,000**, the default schedule charges **145**: 0% on the first 100, 5% on the next 900, and 10% on the final 1,000.

| Payer | Sender loses | Recipient receives |
| --- | ---: | ---: |
| `SENDER` | 2,145 | 2,000 |
| `RECEIVER` | 2,000 | 1,855 |

Permission schedules are selected using the **sender's permissions**, independently of the payer choice. The matching tier with the highest `Priority` replaces the default schedule. Equal priorities use the tier ID in alphabetical order. Profiles do not stack.

- Thresholds must be finite, nonnegative, and unique within a schedule; rates must be between 0 and 100. Malformed bracket fields or payer values reject configuration loading.
- Taxes round down to a whole unit for integer currencies, or to eight decimal places for decimal currencies.
- The sender must afford the full debit, including a sender-paid tax. The recipient's limit applies to the amount actually credited.
- `excellenteconomy.tax.money.bypass` exempts that currency. `excellenteconomy.tax.bypass` is its global parent. These exemption nodes have a false default.
- Taxes apply to player payment commands through `CurrencyManager.send`. Administrative changes, exchange, and third-party Vault deposits/withdrawals/transfers are not taxed.
- Collected tax leaves circulation; there is no treasury account. Transaction logs record the requested amount, tax, debit, credit, and resulting balances. New tax/error messages can be customized through the normal language files.

## Upstream issues

Statuses checked on **2026-10-13**; these changes are local to this fork and do not close upstream issues.

| Reference | Upstream status | Work in this fork |
| --- | --- | --- |
| [#78](https://github.com/nulli0n/ExcellentEconomy/issues/78) | Open | Shared atomic balance changes, guarded payments/withdrawals, recipient limits, and rollback on cancelled events. The original payment body fails the concurrent regression test. |
| [PR #80](https://github.com/nulli0n/ExcellentEconomy/pull/80) | Open, not merged | Used as a reference for scheduling/serialization. The fork uses entity-aware scheduling for Folia and protects both balances plus other API mutations. |
| [#77](https://github.com/nulli0n/ExcellentEconomy/issues/77) | Open | Reproduced the missing optional Vault class during factory verification; isolated optional implementations and declared Paper load/classpath dependencies. The reported exception alone does not establish a Minecraft 26.2 incompatibility. |
| [#70](https://github.com/nulli0n/ExcellentEconomy/issues/70) | Open | Per-currency command permissions, protected root balance fallback, and command suggestion filtering. Fixed the related `standaloneOnly` definition bug. |
| [#67](https://github.com/nulli0n/ExcellentEconomy/issues/67) | Open; maintainer says implemented in 2.7.0 | Kept the existing zero floor. Found and reproduced an additional legacy database-loading path that converted negative rows into positive balances; these now load as zero. Added strict insufficient-fund and invalid-amount regressions. |

## Build and verification

```sh
./gradlew clean test jacocoTestReport build
```

The JAR is written to `build/libs/ExcellentEconomy-2.8.1-mcexp.1.jar`. NightCore and Vault APIs remain external dependencies.

To repeat the API compatibility checks:

```sh
./gradlew test build -PpaperApiVersion=26.1.2.build.74-stable
./gradlew test build -PpaperApiVersion=26.2.build.133-stable
./gradlew test jacocoTestReport build -PpaperApiVersion=26.3.build.178-stable
```

The test suite covers concurrent payments/previews/withdrawals, event cancellation and reentrancy, taxes and permission tiers, configuration parsing, command registration/fallbacks, optional class loading, offline account lifecycle/cold lookups, legacy database rows, shutdown admission, and scheduler dispatch.

Tests use mocked Bukkit services and persistence. **Live server, real SQLite/MySQL integration, and cross-region gameplay tests remain outstanding.** In-memory transaction protection applies within one server; the original cross-server synchronization mechanism is retained and is not a distributed transaction protocol. Coverage and reproducible regression evidence are in [docs/verification.md](docs/verification.md).

For API integrations, balance listeners should inspect/cancel `ChangeBalanceEvent` and schedule follow-up mutations after the event returns. Nested balance edits during a balance event are rejected so that rollback cannot silently undo a successful second transaction. Third-party listeners must schedule their own Bukkit entity/world operations appropriately on Folia.

## Credits and license

Original plugin and architecture: **NightExpress / nulli0n**. Fork changes: **MCExp**. NightCore and Vault/Vault Unlocked remain separate projects. The existing [GPLv3 license](LICENSE) is retained.
