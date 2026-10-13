package su.nightexpress.excellenteconomy.user;

import org.jspecify.annotations.NonNull;

import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.nightcore.user.AbstractUserManager;
import su.nightexpress.nightcore.user.data.DefaultUserDataAccessor;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import java.util.function.Consumer;
import su.nightexpress.excellenteconomy.config.Lang;

public class UserManager extends AbstractUserManager<EconomyPlugin, CoinsUser> {

    private final CurrencyRegistry registry;

    public UserManager(@NonNull EconomyPlugin plugin, @NonNull CurrencyRegistry registry,
                       @NonNull DataHandler dataHandler) {
        super(plugin, new DefaultUserDataAccessor<>(dataHandler, dataHandler));
        this.registry = registry;
    }

    @Override
    @NonNull
    protected CoinsUser create(@NonNull UUID uuid, @NonNull String name, @NonNull InetAddress address) {
        return this.create(uuid, name);
    }

    @NonNull
    public CoinsUser create(@NonNull UUID uuid, @NonNull String name) {
        UserBalance balance = new UserBalance();
        Map<String, CurrencySettings> settingsMap = new HashMap<>();
        long lastSeen = System.currentTimeMillis();
        boolean hiddenFromTops = false;

        this.registry.getCurrencies().forEach(currency -> balance.set(currency, currency.getStartValue()));

        return new CoinsUser(uuid, name, balance, settingsMap, lastSeen, hiddenFromTops);
    }

    @NonNull
    public Optional<CoinsUser> findById(UUID uuid) {
        synchronized (this.repository) {
            return this.getOrFetch(uuid);
        }
    }

    @NonNull
    public Optional<CoinsUser> findByName(String name) {
        synchronized (this.repository) {
            return this.getOrFetch(name);
        }
    }

    public CompletableFuture<Optional<CoinsUser>> findByIdAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> this.findById(uuid));
    }

    public CompletableFuture<Optional<CoinsUser>> findByNameAsync(String name) {
        return CompletableFuture.supplyAsync(() -> this.findByName(name));
    }

    public void withUser(CommandSender sender, String name, Consumer<Optional<CoinsUser>> action) {
        this.findByNameAsync(name).thenAccept(user -> this.plugin.runForSender(sender, () -> action.accept(user)))
            .exceptionally(exception -> {
                this.plugin.error("Could not load economy account '" + name + "': " + exception.getMessage());
                this.plugin.runForSender(sender, () -> Lang.USER_LOOKUP_FAILED.message().send(sender));
                return null;
            });
    }

    public boolean createAccount(UUID uuid, String name) {
        if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) return false;
        synchronized (this.repository) {
            if (this.findById(uuid).isPresent()) return true;
            CoinsUser user = this.create(uuid, name);
            this.dataAccessor.insert(user);
            this.cacheTemporary(user);
            return true;
        }
    }

    public boolean renameAccount(UUID uuid, String name) {
        if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) return false;
        synchronized (this.repository) {
            CoinsUser user = this.findById(uuid).orElse(null);
            if (user == null || user.isOnline()) return false;
            this.repository.getLoadedByNameMap().remove(user.getName().toLowerCase(java.util.Locale.ROOT));
            this.repository.remove(uuid);
            user.setName(name);
            this.dataAccessor.update(user);
            this.cacheTemporary(user);
            return true;
        }
    }

    public boolean deleteAccount(UUID uuid) {
        synchronized (this.repository) {
            CoinsUser user = this.findById(uuid).orElse(null);
            if (user == null || user.isOnline()) return false;
            this.dataAccessor.deleteById(uuid);
            this.repository.getLoadedByNameMap().remove(user.getName().toLowerCase(java.util.Locale.ROOT));
            this.repository.remove(uuid);
            return true;
        }
    }

    public Map<UUID, String> getAccountNames() {
        Map<UUID, String> names = new HashMap<>();
        this.dataAccessor.loadProfiles().forEach(profile -> names.put(profile.id(), profile.name()));
        this.repository.forEach(user -> names.put(user.getId(), user.getName()));
        return Map.copyOf(names);
    }

    @Override
    protected void handleJoin(@NonNull CoinsUser user) {
        user.setLastSeen(System.currentTimeMillis());
    }

    @Override
    protected void handleQuit(@NonNull CoinsUser user) {
        user.setLastSeen(System.currentTimeMillis());
    }

    @Override
    protected void synchronize(@NonNull CoinsUser fetched, @NonNull CoinsUser cached) {
        synchronized (UserBalance.class) {
            if (cached.isDirty()) return;
            for (ExcellentCurrency currency : this.registry.getCurrencies()) {
                if (!currency.isSynchronizable()) continue;
                double balance = fetched.getBalance(currency);
                cached.getBalance().set(currency, balance); // Bypass balance event call.
            }
        }
    }
}
