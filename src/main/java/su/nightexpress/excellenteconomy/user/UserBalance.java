package su.nightexpress.excellenteconomy.user;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

public class UserBalance {

    private final Map<String, Double> balanceMap;

    public UserBalance() {
        this(new ConcurrentHashMap<>());
    }

    public UserBalance(@NonNull Map<String, Double> balanceMap) {
        this.balanceMap = new ConcurrentHashMap<>(balanceMap);
        this.balanceMap.replaceAll((id, amount) -> {
            requireFinite(amount);
            return Math.max(0, amount);
        });
    }

    /**
     * Returns a map of all currency balances.
     * 
     * @return An unmodifiable copy of the balance map.
     */
    @NonNull
    public Map<String, Double> getBalanceMap() {
        synchronized (UserBalance.class) {
            return Map.copyOf(this.balanceMap);
        }
    }

    public void clear() {
        synchronized (UserBalance.class) {
            this.balanceMap.clear();
        }
    }

    public void clear(@NonNull ExcellentCurrency currency) {
        this.clear(currency.getId());
    }

    public void clear(@NonNull String currencyId) {
        synchronized (UserBalance.class) {
            this.balanceMap.remove(currencyId);
        }
    }

    public boolean has(@NonNull ExcellentCurrency currency, double amount) {
        return BalanceTransactions.validAmount(amount) && this.get(currency) >= amount;
    }

    public double get(@NonNull ExcellentCurrency currency) {
        return this.get(currency.getId());
    }

    public double get(@NonNull String currencyId) {
        synchronized (UserBalance.class) {
            return Math.max(0, this.balanceMap.getOrDefault(currencyId, 0D));
        }
    }

    public void add(@NonNull ExcellentCurrency currency, double amount) {
        this.add(currency.getId(), amount);
    }

    public void add(@NonNull String currencyId, double amount) {
        requireFinite(amount);
        synchronized (UserBalance.class) {
            this.balanceMap.compute(currencyId, (k, v) -> {
                double next = (v == null ? 0D : v) + Math.abs(amount);
                requireFinite(next);
                return Math.max(0, next);
            });
        }
    }

    public void remove(@NonNull ExcellentCurrency currency, double amount) {
        this.remove(currency.getId(), amount);
    }

    public void remove(@NonNull String currencyId, double amount) {
        requireFinite(amount);
        synchronized (UserBalance.class) {
            this.balanceMap.compute(currencyId, (k, v) -> Math.max(0, (v == null ? 0D : v) - Math.abs(amount)));
        }
    }

    public void set(@NonNull ExcellentCurrency currency, double amount) {
        this.set(currency.getId(), currency.floorAndLimit(amount));
    }

    public void set(@NonNull String currencyId, double amount) {
        requireFinite(amount);
        synchronized (UserBalance.class) {
            this.balanceMap.put(currencyId, Math.max(0, amount));
        }
    }

    private static void requireFinite(double amount) {
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Balance amount must be finite");
    }
}
