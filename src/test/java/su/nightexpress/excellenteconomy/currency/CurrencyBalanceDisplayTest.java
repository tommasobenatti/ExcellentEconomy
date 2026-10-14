package su.nightexpress.excellenteconomy.currency;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.command.CommandManager;
import su.nightexpress.excellenteconomy.config.Lang;
import su.nightexpress.excellenteconomy.currency.impl.NormalCurrency;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.user.UserManager;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.integration.placeholder.PAPI;
import su.nightexpress.nightcore.locale.entry.MessageLocale;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.placeholder.PlaceholderContext;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrencyBalanceDisplayTest {

    @TempDir Path directory;

    @Test
    void coinsStoreMessageDoesNotLeakIntoOtherCurrenciesOrAmountFormatting() {
        TestServer.initialize();
        try (var items = mockStatic(NightItem.class); var placeholders = mockStatic(PAPI.class)) {
            NightItem icon = mock(NightItem.class);
            items.when(() -> NightItem.fromType(any(Material.class))).thenReturn(icon);
            items.when(() -> NightItem.read(any(FileConfig.class), anyString())).thenReturn(icon);
            placeholders.when(() -> PAPI.setPlaceholders(isNull(), anyString())).thenAnswer(call -> call.getArgument(1));
            FileConfig config = FileConfig.load(directory.resolve("coins.yml"));
            config.set("Balance_Display.Own", List.of("[prefix=\"false\"]%currency_name%: %balance%", "Purchase coins at https://store.survival.net/"));
            config.saveChanges();
            NormalCurrency coins = new NormalCurrency(config.getPath(), "coins");
            coins.load();
            MessageLocale fallback = MessageLocale.chat("fallback", "%currency_name%: %balance%");
            String rendered = PlaceholderContext.builder().with(coins.placeholders())
                .with("%balance%", () -> coins.format(100)).build()
                .apply(coins.getBalanceDisplayOwn(fallback).message().getText());
            assertTrue(rendered.contains("Coins:"));
            assertTrue(rendered.contains("https://store.survival.net/"));
            assertFalse(coins.format(100).contains("store.survival.net"));
            for (String id : List.of("money", "souls")) {
                NormalCurrency currency = new NormalCurrency(directory.resolve(id + ".yml"), id);
                currency.load();
                assertSame(fallback, currency.getBalanceDisplayOwn(fallback));
            }
            config.remove("Balance_Display.Own");
            config.saveChanges();
            coins.load();
            assertSame(fallback, coins.getBalanceDisplayOwn(fallback));
        }
    }

    @Test
    void balanceCommandUsesOwnOverrideAndRetainsGlobalOtherPlayerMessage() {
        TestServer.initialize();
        try (var items = mockStatic(NightItem.class)) {
            NightItem icon = mock(NightItem.class);
            items.when(() -> NightItem.fromType(any(Material.class))).thenReturn(icon);
            items.when(() -> NightItem.read(any(FileConfig.class), anyString())).thenReturn(icon);
            FileConfig config = FileConfig.load(directory.resolve("coins.yml"));
            config.set("Balance_Display.Own", "[prefix=\"false\"]Coins store");
            config.saveChanges();
            NormalCurrency coins = spy(new NormalCurrency(config.getPath(), "coins"));
            coins.load();
            doNothing().when(coins).sendPrefixed(any(MessageLocale.class), any(CommandSender.class), any(Consumer.class));
            var user = TestServer.user("holder", coins, 100);
            Player owner = mock(Player.class), other = mock(Player.class);
            when(owner.getUniqueId()).thenReturn(user.getId());
            when(other.getUniqueId()).thenReturn(UUID.randomUUID());
            EconomyPlugin plugin = mock(EconomyPlugin.class);
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(plugin).runForSender(any(), any(Runnable.class));
            CurrencyManager manager = new CurrencyManager(plugin, new CurrencyRegistry(), mock(CommandManager.class), mock(DataHandler.class), mock(UserManager.class));
            MessageLocale ownMessage = coins.getBalanceDisplayOwn(Lang.CURRENCY_BALANCE_DISPLAY_OWN);
            manager.showBalance(owner, user, coins);
            verify(coins).sendPrefixed(eq(ownMessage), eq(owner), any(Consumer.class));
            manager.showBalance(other, user, coins);
            verify(coins).sendPrefixed(eq(Lang.CURRENCY_BALANCE_DISPLAY_OTHERS), eq(other), any(Consumer.class));
        }
    }
}
