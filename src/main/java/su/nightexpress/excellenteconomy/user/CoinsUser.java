package su.nightexpress.excellenteconomy.user;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.nightcore.user.UserTemplate;

public class CoinsUser extends UserTemplate {

    private static boolean notifyingBalanceChange;
    private long revision;
    private volatile long balanceVersion=-1;
    private final Map<String,Boolean> persistedSettings=new HashMap<>();
    private boolean persistedHidden;
    private java.util.function.BiFunction<ExcellentCurrency, Consumer<UserBalance>, Boolean> persistenceEditor;
    private Consumer<CoinsUser> dirtyListener;

    private final UserBalance                   balance;
    private final Map<String, CurrencySettings> settingsMap;

    private volatile long    lastSeen;
    private volatile boolean hiddenFromTops;

    public CoinsUser(@NonNull UUID uuid,
                     @NonNull String name,
                     @NonNull UserBalance balance,
                     @NonNull Map<String, CurrencySettings> settingsMap,
                     long lastSeen,
                     boolean hiddenFromTops) {
        super(uuid, name);
        this.balance = balance;
        this.settingsMap = new java.util.concurrent.ConcurrentHashMap<>(settingsMap);

        this.lastSeen=lastSeen;
        this.hiddenFromTops=hiddenFromTops;
        this.persistedHidden=hiddenFromTops;
        this.settingsMap.forEach((id,value)->{ persistedSettings.put(id,value.isPaymentsEnabled()); value.onChange(this::markDirty); });
    }

    @Override
    public void markDirty() {
        synchronized (UserBalance.class) {
            super.markDirty(); revision++;
            if (dirtyListener != null) dirtyListener.accept(this);
        }
    }

    public void setPersistenceEditor(java.util.function.BiFunction<ExcellentCurrency,Consumer<UserBalance>,Boolean> editor) { persistenceEditor=editor; }
    public long balanceVersion() { return balanceVersion; }
    public void balanceVersion(long value) { balanceVersion=value; }
    public static boolean balanceChangeInProgress() { synchronized(UserBalance.class) { return notifyingBalanceChange; } }
    public void acceptBalances(CoinsUser fresh) {
        synchronized(UserBalance.class) {
            if(fresh.balanceVersion<balanceVersion) return;
            fresh.getBalance().getBalanceMap().forEach((id,value)->balance.set(id,value));
            balanceVersion=fresh.balanceVersion;
        }
    }
    public void acceptMetadata(CoinsUser fresh) {
        synchronized(UserBalance.class) {
            if(!isDirty()) {
                settingsMap.clear();
                fresh.settingsMap.forEach((id,value)->{ var copy=new CurrencySettings(value.isPaymentsEnabled()); copy.onChange(this::markDirty); settingsMap.put(id,copy); });
                hiddenFromTops=fresh.hiddenFromTops;
                persistedHidden=fresh.hiddenFromTops;
                persistedSettings.clear();
                settingsMap.forEach((id,value)->persistedSettings.put(id,value.isPaymentsEnabled()));
            }
        }
    }

    public Map<String,CurrencySettings> changedSettings() {
        synchronized(UserBalance.class) {
            Map<String,CurrencySettings> changed=new HashMap<>();
            settingsMap.forEach((id,value)->{ if(!java.util.Objects.equals(persistedSettings.get(id),value.isPaymentsEnabled())) changed.put(id,new CurrencySettings(value.isPaymentsEnabled())); });
            return changed;
        }
    }
    public boolean hiddenChanged() { return hiddenFromTops!=persistedHidden; }
    public void acknowledgeMetadata(CoinsUser snapshot) {
        synchronized(UserBalance.class) {
            persistedSettings.clear(); snapshot.settingsMap.forEach((id,value)->persistedSettings.put(id,value.isPaymentsEnabled()));
            persistedHidden=snapshot.hiddenFromTops;
        }
    }

    public void trackChanges(Consumer<CoinsUser> listener) {
        synchronized (UserBalance.class) { dirtyListener = listener; if (isDirty() && listener != null) listener.accept(this); }
    }

    public long revision() { synchronized (UserBalance.class) { return revision; } }

    public CoinsUser snapshot() {
        synchronized (UserBalance.class) {
            Map<String, CurrencySettings> settings = new HashMap<>();
            settingsMap.forEach((id, value) -> settings.put(id, new CurrencySettings(value.isPaymentsEnabled())));
            var copy=new CoinsUser(getId(), getName(), new UserBalance(balance.getBalanceMap()), settings, lastSeen, hiddenFromTops);
            copy.balanceVersion=balanceVersion;
            copy.persistedSettings.clear(); copy.persistedSettings.putAll(persistedSettings); copy.persistedHidden=persistedHidden;
            return copy;
        }
    }

    @Override
    public void markClean() {
        synchronized (UserBalance.class) { super.markClean(); }
    }

    @Override
    public boolean isDirty() {
        synchronized (UserBalance.class) { return super.isDirty(); }
    }

    @NonNull
    public UserBalance getBalance() {
        return this.balance;
    }

    /**
     * Edits user's balance of specific currency and fires the ChangeBalanceEvent event. If event was cancelled, the
     * balance is set back to previous (old) value.
     *
     * @param currency Currency to edit balance of.
     * @param consumer balance function.
     */
    public void editBalance(@NonNull ExcellentCurrency currency, @NonNull Consumer<UserBalance> consumer) {
        this.tryEditBalance(currency, consumer);
    }

    public boolean tryEditBalance(@NonNull ExcellentCurrency currency, @NonNull Consumer<UserBalance> consumer) {
        if(!su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.owns(this)) return false;
        if(persistenceEditor!=null && !su.nightexpress.excellenteconomy.data.postgres.AccountTransaction.active()) {
            if(Thread.holdsLock(UserBalance.class)) return false;
            return persistenceEditor.apply(currency,consumer);
        }
        synchronized (UserBalance.class) {
            if (notifyingBalanceChange) return false;
            double oldBalance = this.getBalance(currency);
            boolean success = false;
            try {
                consumer.accept(this.balance);
                ChangeBalanceEvent event = new ChangeBalanceEvent(this, currency, oldBalance, this.getBalance(currency));
                notifyingBalanceChange = true;
                try {
                    Bukkit.getPluginManager().callEvent(event);
                }
                finally {
                    notifyingBalanceChange = false;
                }
                success = !event.isCancelled();
                if (success) this.markDirty();
                return success;
            }
            finally {
                if (!success) this.balance.set(currency.getId(), oldBalance);
            }
        }
    }

    public void resetBalance(@NonNull Collection<ExcellentCurrency> currencies) {
        currencies.forEach(this::resetBalance);
    }

    public void resetBalance(@NonNull ExcellentCurrency currency) {
        this.editBalance(currency, bal -> bal.set(currency, currency.getStartValue()));
    }

    public boolean hasEnough(@NonNull ExcellentCurrency currency, double amount) {
        return this.balance.has(currency, amount);
    }

    public double getBalance(@NonNull ExcellentCurrency currency) {
        return this.balance.get(currency);
    }

    public void addBalance(@NonNull ExcellentCurrency currency, double amount) {
        this.editBalance(currency, bal -> bal.add(currency, amount));
    }

    public void removeBalance(@NonNull ExcellentCurrency currency, double amount) {
        this.editBalance(currency, lookup -> lookup.remove(currency, amount));
    }

    public void setBalance(@NonNull ExcellentCurrency currency, double amount) {
        this.editBalance(currency, lookup -> lookup.set(currency, amount));
    }

    @NonNull
    public Map<String, CurrencySettings> getSettingsMap() {
        return this.settingsMap;
    }

    @NonNull
    public CurrencySettings getSettings(@NonNull ExcellentCurrency currency) {
        return this.settingsMap.computeIfAbsent(currency.getId(), k -> { var value=CurrencySettings.create(currency); value.onChange(this::markDirty); return value; });
    }

    public long getLastSeen() {
        return this.lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public boolean isHiddenFromTops() {
        return this.hiddenFromTops;
    }

    public void setHiddenFromTops(boolean hiddenFromTops) {
        synchronized(UserBalance.class) {
            if(this.hiddenFromTops!=hiddenFromTops) { this.hiddenFromTops=hiddenFromTops; markDirty(); }
        }
    }
}
