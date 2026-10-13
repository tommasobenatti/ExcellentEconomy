package su.nightexpress.excellenteconomy.user;

import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

public final class BalanceTransactions {

    private BalanceTransactions() {}

    public static boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount >= 0;
    }

    public static boolean deposit(CoinsUser user, ExcellentCurrency currency, double amount) {
        if (!validAmount(amount)) return false;
        double value = currency.floorIfNeeded(amount);
        synchronized (UserBalance.class) {
            double next = user.getBalance(currency) + value;
            return validAmount(next) && currency.isUnderLimit(next)
                && user.tryEditBalance(currency, balance -> balance.set(currency.getId(), next));
        }
    }

    public static boolean withdraw(CoinsUser user, ExcellentCurrency currency, double amount) {
        if (!validAmount(amount)) return false;
        double value = currency.floorIfNeeded(amount);
        synchronized (UserBalance.class) {
            double current = user.getBalance(currency);
            return value <= current
                && user.tryEditBalance(currency, balance -> balance.set(currency.getId(), current - value));
        }
    }

    public static boolean set(CoinsUser user, ExcellentCurrency currency, double amount) {
        return validAmount(amount) && user.tryEditBalance(currency, balance -> balance.set(currency, amount));
    }

    public static boolean transfer(CoinsUser from, ExcellentCurrency source, CoinsUser to, ExcellentCurrency target,
                                   double debit, double credit) {
        if (!validAmount(debit) || !validAmount(credit) || debit <= 0
            || (from.getId().equals(to.getId()) && source.getId().equals(target.getId()))) return false;

        synchronized (UserBalance.class) {
            double fromBalance = from.getBalance(source);
            double toBalance = to.getBalance(target);
            double next = toBalance + credit;
            if (debit > fromBalance || !validAmount(next) || !target.isUnderLimit(next)) return false;

            boolean success = false;
            try {
                success = from.tryEditBalance(source, balance -> balance.set(source.getId(), fromBalance - debit))
                    && to.tryEditBalance(target, balance -> balance.set(target.getId(), next));
                return success;
            }
            finally {
                // Restore both sides if a balance listener rejects or interrupts either change.
                if (!success) {
                    from.getBalance().set(source.getId(), fromBalance);
                    to.getBalance().set(target.getId(), toBalance);
                }
            }
        }
    }
}
