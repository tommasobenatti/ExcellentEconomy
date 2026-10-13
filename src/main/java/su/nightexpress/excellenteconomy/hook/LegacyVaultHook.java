package su.nightexpress.excellenteconomy.hook;

import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.currency.CurrencyManager;
import su.nightexpress.excellenteconomy.currency.impl.AbstractCurrency;
import su.nightexpress.excellenteconomy.currency.impl.EconomyCurrency;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.user.UserManager;

import java.nio.file.Path;

public final class LegacyVaultHook {

    private LegacyVaultHook() {}

    public static AbstractCurrency create(Path path, String id, ExcellentEconomyAPI api,
                                         CurrencyManager manager, DataHandler data, UserManager users) {
        return new EconomyCurrency(path, id, api, manager, data, users);
    }
}
