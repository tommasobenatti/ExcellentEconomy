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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultUnlockedEconomyTest {

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
