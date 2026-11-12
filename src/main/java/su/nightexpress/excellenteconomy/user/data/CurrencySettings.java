package su.nightexpress.excellenteconomy.user.data;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

public class CurrencySettings {

    private volatile boolean paymentsEnabled;
    private Runnable onChange;
    public void onChange(Runnable listener) { this.onChange=listener; }

    public CurrencySettings(boolean paymentsEnabled) {
        this.setPaymentsEnabled(paymentsEnabled);
    }

    @NotNull
    public static CurrencySettings create(@NotNull ExcellentCurrency currency) {
        return new CurrencySettings(true);
    }

    public boolean isPaymentsEnabled() {
        return paymentsEnabled;
    }

    public void setPaymentsEnabled(boolean paymentsEnabled) {
        synchronized(su.nightexpress.excellenteconomy.user.UserBalance.class) {
            boolean changed=this.paymentsEnabled!=paymentsEnabled;
            this.paymentsEnabled=paymentsEnabled;
            if(changed && onChange!=null) onChange.run();
        }
    }
}
