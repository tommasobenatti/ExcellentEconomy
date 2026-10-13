package su.nightexpress.excellenteconomy.hook;

import net.milkbowl.vault2.economy.Economy;
import org.bukkit.plugin.ServicePriority;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;

public final class VaultUnlockedHook {

    private VaultUnlockedHook() {}

    public static Object register(ExcellentEconomyAPI api) {
        VaultUnlockedEconomy provider = new VaultUnlockedEconomy(api);
        api.plugin().getServer().getServicesManager().register(Economy.class, provider, api.plugin(), ServicePriority.High);
        return provider;
    }
}
