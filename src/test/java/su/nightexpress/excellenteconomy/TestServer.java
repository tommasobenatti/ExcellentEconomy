package su.nightexpress.excellenteconomy;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.mockito.MockedStatic;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Registry;
import su.nightexpress.excellenteconomy.currency.tax.TransferTax;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

public final class TestServer {

    public static final PluginManager PLUGINS = mock(PluginManager.class);
    private static final MockedStatic<RegistryAccess> REGISTRIES = mockStatic(RegistryAccess.class);

    static {
        RegistryAccess access = mock(RegistryAccess.class);
        REGISTRIES.when(RegistryAccess::registryAccess).thenReturn(access);
        when(access.getRegistry(any(Class.class))).thenAnswer(call -> mock(Registry.class));
        when(access.getRegistry(any(RegistryKey.class))).thenAnswer(call -> mock(Registry.class));
        Server server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getLogger("ExcellentEconomyTests"));
        when(server.getName()).thenReturn("TestServer");
        when(server.getVersion()).thenReturn("26.1.2");
        when(server.getBukkitVersion()).thenReturn("26.1.2");
        when(server.getPluginManager()).thenReturn(PLUGINS);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getVersionMessage).thenReturn("TestServer");
            Bukkit.setServer(server);
        }
    }

    private TestServer() {}

    public static ExcellentCurrency currency(String id) {
        ExcellentCurrency currency = mock(ExcellentCurrency.class);
        when(currency.getId()).thenReturn(id);
        when(currency.getPermission()).thenReturn("excellenteconomy.currency." + id);
        when(currency.hasPermission(any(org.bukkit.entity.Player.class))).thenReturn(true);
        when(currency.placeholders()).thenReturn(placeholder -> null);
        when(currency.isCommandEnabled(anyString())).thenReturn(true);
        when(currency.getTransferTax()).thenReturn(TransferTax.NONE);
        when(currency.isDecimal()).thenReturn(true);
        when(currency.isTransferAllowed()).thenReturn(true);
        when(currency.isUnlimited()).thenReturn(true);
        when(currency.isUnderLimit(anyDouble())).thenAnswer(call -> Double.isFinite(call.getArgument(0)));
        when(currency.floorIfNeeded(anyDouble())).thenAnswer(call -> Math.max(0D, call.getArgument(0)));
        when(currency.floorAndLimit(anyDouble())).thenAnswer(call -> Math.max(0D, call.getArgument(0)));
        return currency;
    }

    public static CoinsUser user(String name, ExcellentCurrency currency, double balance) {
        return new CoinsUser(UUID.randomUUID(), name, new UserBalance(Map.of(currency.getId(), balance)), Map.of(), 0, false);
    }
}
