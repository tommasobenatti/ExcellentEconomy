# Dungeons reward-target investigation — 2026-10-21

The supplied log excerpt was located in Dungeons' archived log for **2026-10-20**. SFTP access was verified as read-only. No remote configuration, plugin JAR, database, or balance was modified, and no reload/restart was run.

## Verified evidence

- The installed ExcellentEconomy JAR is byte-for-byte identical to the locally built **2.8.1-mcexp.1**. NightCore **2.16.6** and MythicMobs **5.14.0-SNAPSHOT** are installed. The fork compiles against NightCore 2.16.7; the lookup flow was also checked in the installed 2.16.6 bytecode. The version difference alone does not establish a cause.
- The active ExcellentEconomy configuration selects **MySQL**, not the leftover SQLite file. No database contents or credentials are included in this report.
- Five generic player-not-found messages occur in the observed reward session, including **19:37:42**. The supplied successful balance sequence is present. No database exception appears around those reward failures. Connection-pool errors earlier in the day affected several plugins, but are not evidence that these particular five lookups failed for that reason.
- Twelve ordinary-mob reward lines in `plugins/MythicMobs/mobs/dungeon1.yml` and two boss reward lines in `plugins/MythicMobs/mobs/bosses.yml` execute `souls give <trigger.name> AMOUNT` from their drops.
- The old generic error does not include its target. It therefore does **not** establish that the failed command targeted the same player shown in adjacent successful commands.

MythicMobs documents the trigger of a command drop as the entity that killed the mob, which is not necessarily a player. Its drop tables support `TriggerConditions`, and `isPlayer` checks the entity type. This provides a plausible explanation and a valid player-only reward guard; it does not identify the target of a historical command whose target was never logged. Sources: [command drops](https://wiki.mythiccraft.io/mythicmobs/drops/DropTypes/Command), [drop-table trigger conditions](https://wiki.mythiccraft.io/mythicmobs/drops/DropTables), [isPlayer](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/isplayer).

## Diagnostic build

**2.8.1-mcexp.2** logs a missing lookup's target and sender as JSON-escaped strings. The existing callback and player-facing error remain; there are no retry loops, fallback recipients, account creation, reward replays, or changes to balance arithmetic.

Example diagnostic line:

```text
Economy account not found: target="Skeleton", sender="CONSOLE"
```

This is an example, not the observed target at 19:37:42. If a future failure identifies the real player, investigate that account/database lookup instead of assuming the non-player-trigger hypothesis.

`UserManagerTest.missingRewardTargetIsLoggedWithoutCreatingAnAccountOrRetrying` was observed failing before the logging change (**8ea39b4**, RED). The implementation passes that regression and the full **42-test** suite/build (**29b3920**, GREEN). The test also checks escaped newlines, one lookup, and no account insert/update. A real MythicMobs server execution was not reproduced. The release check also caught stale plugin descriptors in an incremental version change; `processResources` now declares the version as an input. Both packaged descriptors were checked to report mcexp.2 after rebuilding.

## Prepared player-only reward configuration

Final local files follow the MCExp convention under `translations/mythicmobs/servers/dungeons/` in the parent workspace:

- `mobs/dungeon1.yml`
- `mobs/bosses.yml`
- `droptables/mcexp_souls.yml`

Only the fourteen Souls command-drop entries are replaced with references to seven guarded reward tables. All amounts (**2, 3, 4, 5, 6, 4000, 5000**), chances, other loot, and all other mob fields are preserved. A Bukkit/NightCore YAML assertion check verifies the complete mob structures and all table references/guards/commands. A byte-reversal check also verifies that all text outside the fourteen replacements is preserved. This validates YAML and the prepared mapping, not a live MythicMobs runtime.

The guard permits only player killers; it does not invent an owner, nearby player, or fallback recipient for pets, other entities, or environmental deaths. It cannot recover historical rewards or prove that a player should have received a historical failed command.

These files are **prepared, not deployed**. The local manifest records the source hashes and status. Before deploying, compare the current remote files to those hashes, retain backups, check for conflicting table IDs, upload only these three paths, reload MythicMobs, and test both a player kill and a non-player/environmental death. Applying the diagnostic JAR requires a normal plugin/server restart. Database/balance restoration is outside this change.
