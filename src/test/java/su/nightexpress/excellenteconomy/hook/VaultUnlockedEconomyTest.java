package su.nightexpress.excellenteconomy.hook;

import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.currency.CurrencyManager;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserManager;

import java.math.BigDecimal;
import java.util.Optional;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.command.CommandManager;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultUnlockedEconomyTest {

    @Test
    void depositsWithdrawalsPreviewsAndTransfersUseTheRealBalanceManager() {
        TestServer.initialize();
        var money = TestServer.currency("money");
        when(money.isPrimary()).thenReturn(true);
        CurrencyRegistry registry = new CurrencyRegistry();
        registry.add(money);
        var source = TestServer.user("source", money, 100);
        var target = TestServer.user("target", money, 0);
        UserManager users = mock(UserManager.class);
        when(users.findById(source.getId())).thenReturn(Optional.of(source));
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        CurrencyManager manager = new CurrencyManager(plugin, registry, mock(CommandManager.class), mock(DataHandler.class), users);
        ExcellentEconomyAPI api = mock(ExcellentEconomyAPI.class);
        when(api.currencyRegistry()).thenReturn(registry);
        when(api.userManager()).thenReturn(users);
        when(api.currencyManager()).thenReturn(manager);
        VaultUnlockedEconomy economy = new VaultUnlockedEconomy(api);

        assertTrue(economy.canDeposit("Shop", source.getId(), BigDecimal.TEN).transactionSuccess());
        assertEquals(100, source.getBalance(money));
        assertTrue(economy.deposit("Shop", source.getId(), BigDecimal.TEN).transactionSuccess());
        assertEquals(110, source.getBalance(money));
        assertFalse(economy.withdraw("Shop", source.getId(), new BigDecimal("111")).transactionSuccess());
        assertTrue(economy.transfer("Shop", source.getId(), target.getId(), new BigDecimal("10")).type == net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS);
        assertEquals(100, source.getBalance(money));
        assertEquals(10, target.getBalance(money));
        doAnswer(call -> {
            ChangeBalanceEvent event = call.getArgument(0);
            if (event.getUser() == target) event.setCancelled(true);
            return null;
        }).when(TestServer.PLUGINS).callEvent(any(ChangeBalanceEvent.class));
        try {
            assertFalse(economy.transfer("Shop", source.getId(), target.getId(), BigDecimal.TEN).type == net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS);
            assertEquals(100, source.getBalance(money));
            assertEquals(10, target.getBalance(money));
        }
        finally { reset(TestServer.PLUGINS); }

        when(money.isUnderLimit(anyDouble())).thenAnswer(call -> (double) call.getArgument(0) <= 50);
        assertFalse(economy.deposit("Shop", source.getId(), BigDecimal.ONE).transactionSuccess());
        assertTrue(economy.canWithdraw("Shop", source.getId(), BigDecimal.ONE).transactionSuccess());
        assertTrue(economy.withdraw("Shop", source.getId(), BigDecimal.ONE).transactionSuccess());
        assertEquals(99, source.getBalance(money));
        assertTrue(economy.set("Shop", source.getId(), new BigDecimal("40")).transactionSuccess());
        assertEquals(40, source.getBalance(money));
    }

    @Test
    void exposesAllCurrenciesAndRejectsInvalidRequests() {
        var money = TestServer.currency("money");
        var coins = TestServer.currency("coins");
        when(money.isPrimary()).thenReturn(true);
        CurrencyRegistry registry = new CurrencyRegistry();
        registry.add(money);
        registry.add(coins);
        CoinsUser user = TestServer.user("user", money, 100);
        user.getBalance().set("coins", 5);
        UserManager users = mock(UserManager.class);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        CurrencyManager manager = mock(CurrencyManager.class);
        when(manager.canPerformOperations()).thenReturn(true);
        ExcellentEconomyAPI api = mock(ExcellentEconomyAPI.class);
        when(api.currencyRegistry()).thenReturn(registry);
        when(api.userManager()).thenReturn(users);
        when(api.currencyManager()).thenReturn(manager);
        VaultUnlockedEconomy economy = new VaultUnlockedEconomy(api);

        assertTrue(economy.hasMultiCurrencySupport());
        assertFalse(economy.hasSharedAccountSupport());
        assertEquals("money", economy.getDefaultCurrency("Shop"));
        assertEquals(2, economy.currencies().size());
        assertEquals(new BigDecimal("5.0"), economy.balance("Shop", user.getId(), "world", "coins"));
        assertFalse(economy.deposit("Shop", user.getId(), "world", "missing", BigDecimal.ONE).transactionSuccess());
        assertFalse(economy.withdraw("Shop", user.getId(), new BigDecimal("-1")).transactionSuccess());
        assertFalse(economy.withdraw("Shop", user.getId(), new BigDecimal("1E400")).transactionSuccess());
        assertFalse(economy.has("Shop", user.getId(), "world", "missing", BigDecimal.ZERO));
        assertEquals(100, user.getBalance(money));
        verify(manager, never()).remove(any(), any(CoinsUser.class), any(), anyDouble());
    }
}
