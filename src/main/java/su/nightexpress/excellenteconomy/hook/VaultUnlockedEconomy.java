package su.nightexpress.excellenteconomy.hook;

import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.NotificationTarget;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext;
import su.nightexpress.excellenteconomy.user.BalanceTransactions;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class VaultUnlockedEconomy implements Economy {

    private final ExcellentEconomyAPI api;

    public VaultUnlockedEconomy(ExcellentEconomyAPI api) {
        this.api = api;
    }

    @Override public boolean isEnabled() { return this.api.plugin().isEnabled(); }
    @Override public String getName() { return "ExcellentEconomy"; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return true; }
    @Override public int fractionalDigits(String pluginName) { return this.fractionalDigits(pluginName, this.getDefaultCurrency(pluginName)); }
    @Override public int fractionalDigits(String pluginName, String currency) { return this.requireCurrency(currency).isDecimal() ? -1 : 0; }
    @Override public String format(BigDecimal amount) { return this.format("VaultUnlocked", amount); }
    @Override public String format(String pluginName, BigDecimal amount) { return this.format(pluginName, amount, this.getDefaultCurrency(pluginName)); }
    @Override public String format(BigDecimal amount, String currency) { return this.format("VaultUnlocked", amount, currency); }
    @Override public String format(String pluginName, BigDecimal amount, String currency) {
        if (!valid(amount)) throw new IllegalArgumentException("Amount must be finite and nonnegative");
        return this.requireCurrency(currency).format(amount.doubleValue());
    }
    @Override public boolean hasCurrency(String currency) { return this.api.currencyRegistry().isRegistered(currency); }
    @Override public String getDefaultCurrency(String pluginName) { return this.api.currencyRegistry().findPrimary().orElseThrow().getId(); }
    @Override public String defaultCurrencyNamePlural(String pluginName) { return this.requireCurrency(this.getDefaultCurrency(pluginName)).getName(); }
    @Override public String defaultCurrencyNameSingular(String pluginName) { return this.defaultCurrencyNamePlural(pluginName); }
    @Override public Collection<String> currencies() { return this.api.currencyRegistry().getCurrencyIds(); }

    private ExcellentCurrency requireCurrency(String id) {
        return this.api.currencyRegistry().byId(id).orElseThrow(() -> new IllegalArgumentException("Unknown currency: " + id));
    }

    @Override public boolean createAccount(UUID accountID, String name) { return this.createAccount(accountID, name, true); }
    @Override public boolean createAccount(UUID accountID, String name, boolean player) { return player && this.api.userManager().createAccount(accountID, name); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName) { return this.createAccount(accountID, name, true); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName, boolean player) { return this.createAccount(accountID, name, player); }
    @Override public Map<UUID, String> getUUIDNameMap() { return this.api.userManager().getAccountNames(); }
    @Override public Optional<String> getAccountName(UUID accountID) { return this.api.userManager().findById(accountID).map(CoinsUser::getName); }
    @Override public boolean hasAccount(UUID accountID) { return this.api.userManager().findById(accountID).isPresent(); }
    @Override public boolean hasAccount(UUID accountID, String worldName) { return this.hasAccount(accountID); }
    @Override public boolean renameAccount(UUID accountID, String name) { return this.api.userManager().renameAccount(accountID, name); }
    @Override public boolean renameAccount(String pluginName, UUID accountID, String name) { return this.renameAccount(accountID, name); }
    @Override public boolean deleteAccount(String pluginName, UUID accountID) { return this.api.userManager().deleteAccount(accountID); }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) { return this.hasCurrency(currency) && this.hasAccount(accountID); }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) { return this.accountSupportsCurrency(pluginName, accountID, currency); }

    @Override public BigDecimal getBalance(String pluginName, UUID accountID) { return this.getBalance(pluginName, accountID, "", this.getDefaultCurrency(pluginName)); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world) { return this.getBalance(pluginName, accountID); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        ExcellentCurrency unit = this.api.currencyRegistry().getById(currency);
        return unit == null ? BigDecimal.ZERO : this.api.userManager().findById(accountID)
            .map(user -> BigDecimal.valueOf(user.getBalance(unit))).orElse(BigDecimal.ZERO);
    }
    @Override public boolean has(String pluginName, UUID accountID, BigDecimal amount) { return this.has(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return this.has(pluginName, accountID, amount); }
    @Override public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return valid(amount) && this.accountSupportsCurrency(pluginName, accountID, currency)
            && this.balance(pluginName, accountID, worldName, currency).compareTo(amount) >= 0;
    }

    private static boolean valid(BigDecimal amount) {
        return amount != null && amount.signum() >= 0 && Double.isFinite(amount.doubleValue());
    }

    private OperationContext context(String pluginName) {
        return OperationContext.custom("Vault Unlocked - " + pluginName)
            .silentFor(NotificationTarget.USER, NotificationTarget.EXECUTOR, NotificationTarget.CONSOLE_LOGGER);
    }

    private EconomyResponse change(String pluginName, UUID accountID, String currencyId, BigDecimal amount, String operation, boolean preview) {
        ExcellentCurrency currency = this.api.currencyRegistry().getById(currencyId);
        CoinsUser user = this.api.userManager().findById(accountID).orElse(null);
        if (currency == null || user == null || !valid(amount)) return failure(BigDecimal.ZERO, "Invalid account, currency, or amount");
        synchronized (UserBalance.class) {
            double value = currency.floorIfNeeded(amount.doubleValue());
            double current = user.getBalance(currency);
            double next = switch (operation) {
                case "deposit" -> current + value;
                case "withdraw" -> current - value;
                default -> value;
            };
            if (!this.api.currencyManager().canPerformOperations() || !BalanceTransactions.validAmount(next) || !currency.isUnderLimit(next))
                return failure(BigDecimal.valueOf(current), "Insufficient funds, balance limit, or disabled operations");
            boolean success = preview || switch (operation) {
                case "deposit" -> this.api.currencyManager().give(this.context(pluginName), user, currency, value).success();
                case "withdraw" -> this.api.currencyManager().remove(this.context(pluginName), user, currency, value).success();
                default -> this.api.currencyManager().set(this.context(pluginName), user, currency, value).success();
            };
            if (!success) return failure(BigDecimal.valueOf(user.getBalance(currency)), "Balance change was rejected");
            return new EconomyResponse(BigDecimal.valueOf(value), BigDecimal.valueOf(preview ? current : user.getBalance(currency)),
                EconomyResponse.ResponseType.SUCCESS, "");
        }
    }

    private static EconomyResponse failure(BigDecimal balance, String message) {
        return new EconomyResponse(BigDecimal.ZERO, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    @Override public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) { return this.deposit(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return this.deposit(pluginName, accountID, amount); }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, "deposit", false); }

    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) { return this.withdraw(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return this.withdraw(pluginName, accountID, amount); }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, "withdraw", false); }

    @Override public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) { return this.set(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse set(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return this.set(pluginName, accountID, amount); }
    @Override public EconomyResponse set(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, "set", false); }

    @Override public EconomyResponse canDeposit(String pluginName, UUID accountID, BigDecimal amount) { return this.canDeposit(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return this.canDeposit(pluginName, accountID, amount); }
    @Override public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, "deposit", true); }

    @Override public EconomyResponse canWithdraw(String pluginName, UUID accountID, BigDecimal amount) { return this.canWithdraw(pluginName, accountID, "", this.getDefaultCurrency(pluginName), amount); }
    @Override public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return this.canWithdraw(pluginName, accountID, amount); }
    @Override public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) { return this.change(pluginName, accountID, currency, amount, "withdraw", true); }

    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) {
        return this.transfer(pluginName, from, to, "", this.getDefaultCurrency(pluginName), amount);
    }
    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, BigDecimal amount) {
        return this.transfer(pluginName, from, to, amount);
    }
    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, String currencyId, BigDecimal amount) {
        ExcellentCurrency currency = this.api.currencyRegistry().getById(currencyId);
        CoinsUser sender = this.api.userManager().findById(from).orElse(null);
        CoinsUser receiver = this.api.userManager().findById(to).orElse(null);
        if (currency == null || sender == null || receiver == null || !valid(amount))
            return new MultiEconomyResponse(BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, "Invalid account, currency, or amount");
        synchronized (UserBalance.class) {
            double value = currency.floorIfNeeded(amount.doubleValue());
            if (!this.api.currencyManager().transfer(this.context(pluginName), sender, receiver, currency, value).success())
                return new MultiEconomyResponse(BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, "Transfer was rejected");
            MultiEconomyResponse response = new MultiEconomyResponse(BigDecimal.valueOf(value), EconomyResponse.ResponseType.SUCCESS, "");
            response.addBalance(from, BigDecimal.valueOf(sender.getBalance(currency)));
            response.addBalance(to, BigDecimal.valueOf(receiver.getBalance(currency)));
            return response;
        }
    }

    @Override public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) { return false; }
    @Override public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean setOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) { return false; }
    @Override public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) { return false; }
    @Override public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) { return false; }
}
