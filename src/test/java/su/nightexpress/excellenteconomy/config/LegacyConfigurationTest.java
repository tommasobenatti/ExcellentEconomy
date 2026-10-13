package su.nightexpress.excellenteconomy.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.command.currency.CommandDefinition;
import su.nightexpress.excellenteconomy.currency.impl.NormalCurrency;
import su.nightexpress.excellenteconomy.tops.TopLang;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.config.PluginDetails;
import su.nightexpress.nightcore.db.config.DatabaseConfig;
import su.nightexpress.nightcore.db.config.DatabaseType;
import su.nightexpress.nightcore.integration.placeholder.PAPI;
import su.nightexpress.nightcore.util.bukkit.NightItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyConfigurationTest {

    @TempDir Path directory;

    private Path copy(String name) throws Exception {
        TestServer.initialize();
        Path target = directory.resolve(name);
        Files.createDirectories(target.getParent());
        String supplied = System.getenv("EXCELLENTECONOMY_CONFIG_CHECK");
        if (supplied != null) {
            Files.copy(Path.of(supplied).resolve(name), target);
        }
        else {
            try (var input = getClass().getResourceAsStream("/configuration/legacy-server/" + name)) {
                assertNotNull(input);
                Files.copy(input, target);
            }
        }
        return target;
    }

    @Test
    void migratesObsoleteCompactSuffixAndPreservesBothCurrencies() throws Exception {
        try (MockedStatic<NightItem> items = mockStatic(NightItem.class);
             MockedStatic<PAPI> placeholders = mockStatic(PAPI.class)) {
            NightItem icon = mock(NightItem.class);
            items.when(() -> NightItem.fromType(any(Material.class))).thenReturn(icon);
            items.when(() -> NightItem.read(any(FileConfig.class), anyString())).thenReturn(icon);
            placeholders.when(() -> PAPI.setPlaceholders(isNull(), anyString())).thenAnswer(call -> call.getArgument(1));

            for (String id : List.of("coins", "souls")) {
                Path path = copy("currencies/" + id + ".yml");
                FileConfig before = FileConfig.load(path);
                NormalCurrency currency = new NormalCurrency(path, id);
                currency.load();

                assertEquals("%currency_symbol%%amount%", currency.getFormatShort());
                assertFalse(currency.formatCompact(1_500).contains("%currency_short_symbol%"));
                assertEquals(id, currency.getColumnName());
                assertEquals(0, currency.getStartValue());
                assertEquals(-1, currency.getMaxValue());
                assertFalse(currency.isDecimal());
                assertFalse(currency.isSynchronizable());
                assertFalse(currency.isTransferAllowed());
                assertFalse(currency.isExchangeAllowed());
                assertFalse(currency.isPermissionRequired());
                assertFalse(currency.getTransferTax().enabled());
                assertTrue(currency.isCommandEnabled("balance"));
                assertTrue(currency.isCommandEnabled("give"));
                assertArrayEquals(new String[]{id, id.equals("coins") ? "coin" : "soul"}, currency.getCommandAliases());

                FileConfig after = FileConfig.load(path);
                before.getValues(true).forEach((key, value) -> {
                    if (!(value instanceof ConfigurationSection) && !key.equals("Format_Short")) {
                        assertEquals(value, after.get(key), "Changed existing currency setting: " + key);
                    }
                });
                assertEquals(currency.getFormatShort(), after.getString("Format_Short"));
                String migrated = Files.readString(path);
                currency.load();
                assertEquals(migrated, Files.readString(path), "Currency migration must be idempotent");
            }
        }
    }

    @Test
    void preservesStorageAndPluginAliasesWhileAddingOnlyMissingSettings() throws Exception {
        FileConfig engine = FileConfig.load(copy("engine.yml"));
        FileConfig main = FileConfig.load(copy("config.yml"));
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        when(plugin.getName()).thenReturn("ExcellentEconomy");
        when(plugin.getEngineConfig()).thenReturn(engine);
        when(plugin.getConfig()).thenReturn(main);

        PluginDetails details = PluginDetails.read(plugin, engine, PluginDetails.create("Economy", new String[]{"execo"}));
        assertArrayEquals(new String[]{"coinsengine", "coe"}, details.getCommandAliases());
        assertEquals("en", details.getLanguage());
        DatabaseConfig database = DatabaseConfig.read(plugin);
        assertEquals(DatabaseType.SQLITE, database.getStorageType());
        assertEquals("coinsengine", database.getTablePrefix());
        assertEquals("data.db", database.getFilename());
        assertFalse(database.isPurgeEnabled());
        assertEquals(-1, database.getSyncInterval());
        assertEquals(20, engine.getInt("UserData.Scheduled_Save_Interval"));

        var previous = main.getValues(true);
        main.initializeOptions(Config.class);
        previous.forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) assertEquals(value, main.get(key), key);
        });
        assertTrue(main.getBoolean("Integration.VaultUnlocked.Enabled"));
        assertEquals(300, Config.TOPS_UPDATE_INTERVAL.get());
        assertEquals(previous.get("Integration.Vault.EconomyCurrency"), Config.INTEGRATION_VAULT_ECONOMY_CURRENCY.get());
        main.saveChanges();
        assertEquals("coinsengine", FileConfig.load(engine.getPath()).getString("Database.Table_Prefix"));
    }

    @Test
    void keepsCurrentCommandChoicesAndReadsOldAliasSections() throws Exception {
        FileConfig commands = FileConfig.load(copy("commands.yml"));
        CommandDefinition give = CommandDefinition.read(commands, "Commands.give");
        assertEquals("give", give.childrenAlias());
        assertFalse(give.standaloneEnabled());
        assertArrayEquals(new String[]{"ecogive"}, give.standaloneAliases());
        CommandDefinition send = CommandDefinition.read(commands, "Commands.send");
        assertTrue(send.standaloneEnabled());
        assertArrayEquals(new String[]{"pay"}, send.standaloneAliases());
        CommandDefinition old = CommandDefinition.read(commands, "Commands.add");
        assertEquals("give", old.childrenAlias());
        assertFalse(old.standaloneEnabled());
        assertArrayEquals(new String[]{"addmoney"}, old.standaloneAliases());
        commands.saveChanges();
        assertFalse(FileConfig.load(commands.getPath()).contains("Commands.add.Dedicated"));
        assertTrue(CommandDefinition.read(commands, "Commands.balance").standaloneEnabled());
    }

    @Test
    void keepsCustomNotificationsAndLeaderboardAppearance() throws Exception {
        FileConfig messages = FileConfig.load(copy("lang/lang_en.yml"));
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        Lang.COMMAND_CURRENCY_GIVE_NOTIFY.load(plugin, messages);
        assertEquals("<gray>+%amount% %currency_prefix%", Lang.COMMAND_CURRENCY_GIVE_NOTIFY.message().getText());
        TopLang.UI_LEADERBOARD_ENTRY.load(plugin, messages);
        assertEquals("<yellow>#%pos%</yellow> <white>%player_name%</white>", TopLang.UI_LEADERBOARD_ENTRY.getName());
        assertEquals(List.of("<green>%balance%</green>"), TopLang.UI_LEADERBOARD_ENTRY.getLore());
        FileConfig menu = FileConfig.load(copy("menu/leaderboard.yml"));
        assertEquals("minecraft:generic_9x5", menu.getString("Settings.MenuType"));
        assertEquals(36, menu.getIntArray("Entry.Slots").length);
        assertEquals("44", menu.getString("Buttons.next_page.Slots"));
    }
}
