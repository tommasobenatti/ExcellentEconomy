package su.nightexpress.excellenteconomy.currency;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import su.nightexpress.excellenteconomy.EconomyFiles;
import su.nightexpress.excellenteconomy.EconomyPlaceholders;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.NotificationTarget;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationExecutor;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;
import su.nightexpress.excellenteconomy.command.CommandManager;
import su.nightexpress.excellenteconomy.command.currency.CommandDefinition;
import su.nightexpress.excellenteconomy.config.Config;
import su.nightexpress.excellenteconomy.config.Lang;
import su.nightexpress.excellenteconomy.config.Perms;
import su.nightexpress.excellenteconomy.currency.tax.TransferTax;
import su.nightexpress.excellenteconomy.currency.command.*;
import su.nightexpress.excellenteconomy.currency.impl.AbstractCurrency;
import su.nightexpress.excellenteconomy.currency.impl.NormalCurrency;
import su.nightexpress.excellenteconomy.currency.placeholder.PlayerBalancePlaceholders;
import su.nightexpress.excellenteconomy.data.DataColumns;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.hook.HookPlugin;
import su.nightexpress.excellenteconomy.hook.VaultHooks;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserBalance;
import su.nightexpress.excellenteconomy.user.BalanceTransactions;
import su.nightexpress.excellenteconomy.user.UserManager;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.core.config.CoreLang;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.util.FileUtil;
import su.nightexpress.nightcore.util.Plugins;
import su.nightexpress.nightcore.util.Strings;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.placeholder.CommonPlaceholders;
import su.nightexpress.nightcore.util.placeholder.PlaceholderContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CurrencyManager extends AbstractManager<EconomyPlugin> {

    private final CurrencyRegistry registry;
    private final CommandManager   commandManager;
    private final DataHandler      dataHandler;
    private final UserManager      userManager;

    private volatile boolean operationsAllowed;
    private boolean shuttingDown;
    private CurrencyLogger logger;

    public CurrencyManager(@NonNull EconomyPlugin plugin,
                           @NonNull CurrencyRegistry registry,
                           @NonNull CommandManager commandManager,
                           @NonNull DataHandler dataHandler,
                           @NonNull UserManager userManager) {
        super(plugin);
        this.registry = registry;
        this.commandManager = commandManager;
        this.dataHandler = dataHandler;
        this.userManager = userManager;
        this.allowOperations();
    }

    @Override
    protected void onLoad() {
        this.createDefaults();
        this.migrateSettings();
        this.loadPluginCommands();
        this.loadCurrencyCommands();
        this.plugin.addGlobalPlaceholders(new PlayerBalancePlaceholders(this.registry, this));

        FileUtil.findYamlFiles(this.getDirectory()).forEach(this::loadCurrency);

        try {
            this.loadLogger();
        }
        catch (IOException | IllegalArgumentException exception) {
            this.plugin.error("Could not create operations logger: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    @Override
    protected void onShutdown() {
        this.prepareShutdown();
        this.registry.getCurrencies().forEach(this::unregisterCurrency);

        if (this.logger != null) this.logger.shutdown();
        this.disableOperations();
    }

    @NonNull
    public String getDirectory() {
        return this.plugin.getDataFolder() + EconomyFiles.DIR_CURRENCIES;
    }

    public void allowOperations() {
        synchronized (UserBalance.class) {
            if (this.shuttingDown) return;
            this.operationsAllowed = true;
            this.dataHandler.setSynchronizationActive(true);
        }
    }

    public void disableOperations() {
        synchronized (UserBalance.class) {
            this.operationsAllowed = false;
            this.dataHandler.setSynchronizationActive(false);
        }
    }

    public boolean beginMaintenance() {
        synchronized (UserBalance.class) {
            if (!this.operationsAllowed) return false;
            this.disableOperations();
            return true;
        }
    }

    public void prepareShutdown() {
        synchronized (UserBalance.class) {
            this.shuttingDown = true;
            this.disableOperations();
        }
    }

    public boolean canPerformOperations() {
        return this.operationsAllowed;
    }

    private boolean assertOperationsEnabled(@NonNull OperationContext context) {
        if (!this.canPerformOperations()) {
            context.getBukkitSender().ifPresent(sender -> this.plugin.runForSender(sender, () -> Lang.CURRENCY_OPERATION_DISABLED.message().send(sender)));
            return false;
        }
        return true;
    }

    private void migrateSettings() {
        FileUtil.findYamlFiles(this.getDirectory()).forEach(path -> {
            String fileName = path.getFileName().toString();
            if (!fileName.endsWith(FileConfig.EXTENSION)) return;

            FileConfig config = FileConfig.load(path);
            if (!config.contains("Economy")) return;

            if (config.getBoolean("Economy.Vault")) {
                String name = fileName.substring(0, fileName.length() - FileConfig.EXTENSION.length());
                Config.INTEGRATION_VAULT_ECONOMY_CURRENCY.set(name);
                Config.INTEGRATION_VAULT_ECONOMY_CURRENCY.write(this.plugin.getConfig());
            }

            config.remove("Economy");
            config.saveChanges();
        });
    }

    private void loadPluginCommands() {
        this.commandManager.addPluginCommand(ResetAllCommand.create(this.registry, this));
        this.commandManager.addPluginCommand(CreateCommand.create(this));

        if (Config.isWalletEnabled()) {
            this.commandManager.addStandaloneCommand(WalletCommand.create(this.plugin, this, this.userManager));
        }
    }

    private void loadCurrencyCommands() {
        this.commandManager.addCurrencyCommand("balance",
            () -> new BalanceCommand(this, this.userManager),
            CommandDefinition.allEnabled("balance", "balance", "bal")
        );

        this.commandManager.addCurrencyCommand("exchange",
            () -> new ExchangeCommand(this.registry, this),
            CommandDefinition.childOnly("exchange", "ecoexchange"),
            ExcellentCurrency::isExchangeAllowed
        );

        this.commandManager.addCurrencyCommand("giveall",
            () -> new GiveAllCommand(this),
            CommandDefinition.childOnly("giveall", "ecogiveall")
        );

        this.commandManager.addCurrencyCommand("give",
            () -> new GiveCommand(this, this.userManager),
            CommandDefinition.childOnly("give", "ecogive")
        );

        this.commandManager.addCurrencyCommand("payments",
            () -> new PaymentsCommand(this, this.userManager),
            CommandDefinition.allEnabled("payments", "paytoggle", "payments"),
            ExcellentCurrency::isTransferAllowed
        );

        this.commandManager.addCurrencyCommand("remove",
            () -> new RemoveCommand(this, this.userManager),
            CommandDefinition.allEnabled("take", "ecotake")
        );

        this.commandManager.addCurrencyCommand("reset",
            () -> new ResetCommand(this, this.userManager),
            CommandDefinition.childOnly("reset", "ecoreset")
        );

        this.commandManager.addCurrencyCommand("send",
            () -> new PayCommand(this, this.userManager),
            CommandDefinition.allEnabled("pay", "pay"),
            ExcellentCurrency::isTransferAllowed
        );

        this.commandManager.addCurrencyCommand("set",
            () -> new SetCommand(this, this.userManager),
            CommandDefinition.allEnabled("set", "ecoset")
        );
    }

    private void loadCurrency(@NonNull Path path) throws IllegalStateException {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(FileConfig.EXTENSION)) return;

        String name = fileName.substring(0, fileName.length() - FileConfig.EXTENSION.length());
        String id = Strings.varStyle(name).orElseThrow(() -> new IllegalStateException("Malformed file name '" +
            fileName + "'"));

        boolean isVault = Config.INTEGRATION_VAULT_ENABLED.get()
            && VaultHooks.hasAPI("net.milkbowl.vault.economy.Economy");
        boolean isGoodId = Config.INTEGRATION_VAULT_ECONOMY_CURRENCY.get().equalsIgnoreCase(id);

        AbstractCurrency currency;

        if (isVault && isGoodId) {
            currency = CurrencyFactory.createEconomy(path, id, this.plugin.getAPI(), this, this.dataHandler,
                this.userManager);
        }
        else {
            currency = new NormalCurrency(path, id, isGoodId);
        }

        // Useless until we remake the plugin reload system.
        if (currency.isPrimary() && this.registry.hasPrimary()) {
            this.plugin.warn("Could not load primary currency '" + currency.getId() +
                "' as there is already one present. Reboot the server if you want to change your primary currency.");
            return;
        }

        currency.load();

        this.registerCurrency(currency);
    }

    private void createDefaults() {
        File dir = new File(this.getDirectory());
        if (dir.exists()) return;

        this.createCurrency("coins", currency -> {
            currency.setSymbol("⛂");
            currency.setIcon(NightItem.fromType(Material.SUNFLOWER));
            currency.setDecimal(false);
        });

        this.createCurrency("money", currency -> {
            currency.setSymbol("$");
            currency.setFormat(EconomyPlaceholders.CURRENCY_SYMBOL + EconomyPlaceholders.GENERIC_AMOUNT);
            currency.setFormat(currency.getFormat());
            currency.setIcon(NightItem.fromType(Material.GOLD_NUGGET));
            currency.setDecimal(true);
        });
    }

    private void loadLogger() throws IOException, IllegalArgumentException {
        boolean logToConsole = Config.LOGS_TO_CONSOLE.get();
        boolean logToFile = Config.LOGS_TO_FILE.get();
        if (!logToConsole && !logToFile) return;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Config.LOGS_DATE_FORMAT.get());
        Path filePath = Paths.get(this.plugin.getDataFolder().getAbsolutePath(), EconomyFiles.FILE_OPERATIONS);

        this.logger = new CurrencyLogger(this.plugin, formatter, filePath, logToConsole, logToFile);
        this.addAsyncTask(() -> this.logger.write(), Config.LOGS_WRITE_INTERVAL.get());
    }

    public boolean registerCurrency(@NonNull ExcellentCurrency currency) {
        if (this.registry.isRegistered(currency.getId())) {
            this.plugin.error("Could not register duplicated currency: '" + currency.getId() + "'!");
            return false;
        }

        if (DataColumns.containsCurrency(currency)) {
            this.plugin.error("Currency '" + currency.getId() + "' tried to use column '" + currency.getColumnName() +
                "' which is already used by other currency!");
            return false;
        }

        Perms.registerChild(Perms.taxBypass(currency), Perms.TAX_BYPASS);
        currency.onRegister();
        this.registry.add(currency);
        this.dataHandler.onCurrencyRegister(currency);

        this.plugin.info("Currency registered: '" + currency.getId() + "'.");
        return true;
    }

    /**
     * Injects a new currency into the plugin, making it available for use by players
     * and other integrated plugins.
     *
     * <p>Unlike {@link #registerCurrency(ExcellentCurrency)}, this method also handles the
     * registration of commands associated with the currency if they are not
     * already present.</p>
     *
     * @param currency the non-null {@link ExcellentCurrency} instance to be injected
     * @return {@code true} if the currency was successfully injected;
     *         {@code false} if the injection failed or the currency was already managed
     */
    public boolean injectCurrency(@NonNull ExcellentCurrency currency) {
        if (!this.registerCurrency(currency)) return false;

        if (!this.commandManager.hasRegisteredCommands(currency)) {
            this.commandManager.registerCurrencyCommands(currency);
        }

        return true;
    }

    public boolean unregisterCurrency(@NonNull ExcellentCurrency currency) {
        return this.unregisterCurrency(currency.getId());
    }

    public boolean unregisterCurrency(@NonNull String id) {
        ExcellentCurrency currency = this.registry.remove(id);
        if (currency == null) return false;

        currency.onUnregister();
        this.dataHandler.onCurrencyUnload(currency);
        this.plugin.info("Currency unregistered: '" + currency.getId() + "'.");
        return true;
    }

    @NonNull
    public NormalCurrency createCurrency(@NonNull String id, @NonNull Consumer<NormalCurrency> consumer) {
        Path path = Paths.get(this.getDirectory(), FileConfig.withExtension(id));
        NormalCurrency currency = new NormalCurrency(path, id);

        consumer.accept(currency);
        currency.write();
        return currency;
    }

    public boolean createCurrency(@NonNull CommandSender sender, @NonNull String name, @NonNull String symbol,
                                  boolean decimals) {
        String id = Strings.varStyle(name).orElse(null);
        if (id == null) {
            Lang.CURRENCY_CREATE_BAD_NAME.message().send(sender);
            return false;
        }

        if (this.registry.isRegistered(id)) {
            Lang.CURRENCY_CREATE_DUPLICATED.message().send(sender);
            return false;
        }

        NormalCurrency created = this.createCurrency(id, currency -> {
            currency.setSymbol(symbol);
            currency.setDecimal(decimals);
        });

        created.updateMessagePrefix();

        this.injectCurrency(created);

        Lang.CURRENCY_CREATE_SUCCESS.message().sendWith(sender, builder -> builder.with(created.placeholders()));
        return true;
    }

    public void resetBalances(@NonNull CommandSender sender) {
        this.resetBalances(sender, null);
    }

    public void resetBalances(@NonNull CommandSender sender, @Nullable ExcellentCurrency currency) {
        if (!this.beginMaintenance()) {
            Lang.RESET_ALL_START_BLOCKED.message().send(sender);
            return;
        }

        this.plugin.runTaskAsync(() -> {
            try {
                Collection<ExcellentCurrency> currencies = currency == null ? this.registry.getCurrencies() : Set.of(currency);
                this.plugin.runForSender(sender, () -> {
                    if (currency == null) Lang.RESET_ALL_STARTED_GLOBAL.message().send(sender);
                    else Lang.RESET_ALL_STARTED_CURRENCY.message().sendWith(sender, builder -> builder.with(currency.placeholders()));
                });
                this.dataHandler.resetBalances(currencies);
                this.userManager.getRepository().getAll().forEach(user -> user.resetBalance(currencies));
                this.plugin.runForSender(sender, () -> {
                    if (currency == null) Lang.RESET_ALL_COMPLETED_GLOBAL.message().send(sender);
                    else Lang.RESET_ALL_COMPLETED_CURRENCY.message().sendWith(sender, builder -> builder.with(currency.placeholders()));
                });
            }
            finally {
                this.allowOperations();
            }
        });
    }

    public boolean getPaymentsState(@NonNull Player player, @NonNull ExcellentCurrency currency) {
        return this.getPaymentsState(this.userManager.getOrFetch(player), currency);
    }

    public boolean getPaymentsState(@NonNull CoinsUser user, @NonNull ExcellentCurrency currency) {
        return user.getSettings(currency).isPaymentsEnabled();
    }

    public void showBalance(@NonNull Player player, @NonNull ExcellentCurrency currency) {
        this.showBalance(player, this.userManager.getOrFetch(player), currency);
    }

    public void showBalance(@NonNull CommandSender sender, @NonNull CoinsUser user,
                            @NonNull ExcellentCurrency currency) {
        this.plugin.runForSender(sender, () -> currency.sendPrefixed((user.isHolder(
            sender) ? Lang.CURRENCY_BALANCE_DISPLAY_OWN : Lang.CURRENCY_BALANCE_DISPLAY_OTHERS), sender,
            builder -> builder
                .with(CommonPlaceholders.PLAYER_NAME, user::getName)
                .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
        ));
    }

    public boolean showWallet(@NonNull Player player) {
        return this.showWallet(player, this.userManager.getOrFetch(player));
    }

    public boolean showWallet(@NonNull CommandSender sender, @NonNull CoinsUser user) {
        this.plugin.runForSender(sender, () -> (user.isHolder(sender) ? Lang.CURRENCY_WALLET_OWN : Lang.CURRENCY_WALLET_OTHERS).message().sendWith(sender,
            builder -> builder
                .with(EconomyPlaceholders.GENERIC_ENTRY, () -> this.registry.stream()
                    .filter(currency -> !(sender instanceof Player player) || currency.hasPermission(player))
                    .sorted(Comparator.comparing(ExcellentCurrency::getId))
                    .map(currency -> PlaceholderContext.builder()
                        .with(currency.placeholders())
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                        .build()
                        .apply(Lang.CURRENCY_WALLET_ENTRY.text())
                    ).collect(Collectors.joining("\n")))
                .with(CommonPlaceholders.PLAYER_NAME, user::getName)
        ));

        return true;
    }

    public boolean togglePayments(@NonNull Player player, @NonNull ExcellentCurrency currency) {
        CoinsUser user = this.userManager.getOrFetch(player);

        return this.togglePayments(user, currency, false);
    }

    public boolean togglePayments(@NonNull CoinsUser user, @NonNull ExcellentCurrency currency, boolean silent) {
        CurrencySettings settings = user.getSettings(currency);
        synchronized (settings) {
            settings.setPaymentsEnabled(!settings.isPaymentsEnabled());
        }
        user.markDirty();

        Player target = user.player().orElse(null);
        if (!silent && target != null) {
            this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_PAYMENTS_TOGGLE, target, builder -> builder
                .with(EconomyPlaceholders.GENERIC_STATE, () -> CoreLang.STATE_ENABLED_DISALBED.get(settings
                    .isPaymentsEnabled()))
            ));
        }

        return true;
    }

    public double getBalance(@NonNull Player player, @NonNull ExcellentCurrency currency) {
        return this.getBalance(this.userManager.getOrFetch(player), currency);
    }

    public double getBalance(@NonNull CoinsUser user, @NonNull ExcellentCurrency currency) {
        return user.getBalance(currency);
    }

    @NonNull
    public OperationResult give(@NonNull OperationContext context, @NonNull Player player,
                                @NonNull ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            return this.give(context, this.userManager.getOrFetch(player), currency, amount);

        }
    }

    @NonNull
    public OperationResult give(@NonNull OperationContext context, @NonNull CoinsUser user,
                                @NonNull ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            if (!this.assertOperationsEnabled(context)) return OperationResult.FAILURE;

            OperationExecutor executor = context.getExecutor();

            if (!BalanceTransactions.deposit(user, currency, amount)) return OperationResult.FAILURE;
            user.markDirty();

            if (this.logger != null && context.shouldNotifyLogger()) {
                this.logger.addEntry(context, "[%s] %s gave %s to %s. New balance: %s"
                    .formatted(currency.getId(), executor.getName(), currency.format(amount), user.getName(), currency
                        .format(user.getBalance(currency)))
                );
            }

            if (context.shouldNotify(NotificationTarget.EXECUTOR)) {
                executor.getBukkitSender().ifPresent(sender -> {
                    this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_GIVE_DONE, sender, builder -> builder
                        .with(CommonPlaceholders.PLAYER_NAME, user::getName)
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            if (context.shouldNotify(NotificationTarget.USER)) {
                user.player().ifPresent(target -> this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_GIVE_NOTIFY, target,
                    builder -> builder
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                )));
            }

            return OperationResult.SUCCESS;

        }
    }

    @NonNull
    public OperationResult giveAll(@NonNull OperationContext context, @NonNull ExcellentCurrency currency,
                                   double amount) {
        synchronized (UserBalance.class) {
            if (!this.assertOperationsEnabled(context)) return OperationResult.FAILURE;

            if (!BalanceTransactions.validAmount(amount)) return OperationResult.FAILURE;
            OperationExecutor executor = context.getExecutor();
            Set<CoinsUser> users = this.userManager.getRepository().getAll();
            int size = users.size();
            String names = users.stream().map(CoinsUser::getName).collect(Collectors.joining(", "));

            users.forEach(user -> {
                Player target = user.player().orElse(null);
                if (target == null) return; // Only online players should be affected.

                if (!BalanceTransactions.deposit(user, currency, amount)) return;
                user.markDirty();

                if (context.shouldNotify(NotificationTarget.USER)) {
                    this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_GIVE_NOTIFY, target, builder -> builder
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                }
            });

            if (this.logger != null && context.shouldNotifyLogger()) {
                this.logger.addEntry(context, "[%s] %s gave %s to all online players. Affected players (%s): %s"
                    .formatted(currency.getId(), executor.getName(), currency.format(amount), size, names)
                );
            }

            if (context.shouldNotify(NotificationTarget.EXECUTOR)) {
                executor.getBukkitSender().ifPresent(sender -> {
                    this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_GIVE_ALL_DONE, sender, builder -> builder
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                    ));
                });
            }

            return OperationResult.SUCCESS;

        }
    }

    @NonNull
    public OperationResult remove(@NonNull OperationContext context, @NonNull Player player,
                                  @NonNull ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            return this.remove(context, this.userManager.getOrFetch(player), currency, amount);

        }
    }

    @NonNull
    public OperationResult remove(@NonNull OperationContext context, @NonNull CoinsUser user,
                                  @NonNull ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            if (!this.assertOperationsEnabled(context)) return OperationResult.FAILURE;

            OperationExecutor executor = context.getExecutor();

            if (!BalanceTransactions.withdraw(user, currency, amount)) return OperationResult.FAILURE;
            user.markDirty();

            if (this.logger != null && context.shouldNotifyLogger()) {
                this.logger.addEntry(context, "[%s] %s took %s from %s's balance. New balance: %s"
                    .formatted(currency.getId(), executor.getName(), currency.format(amount), user.getName(), currency
                        .format(user.getBalance(currency))));
            }

            if (context.shouldNotify(NotificationTarget.EXECUTOR)) {
                executor.getBukkitSender().ifPresent(sender -> {
                    this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_TAKE_DONE, sender, builder -> builder
                        .with(CommonPlaceholders.PLAYER_NAME, user::getName)
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            if (context.shouldNotify(NotificationTarget.USER)) {
                user.player().ifPresent(target -> {
                    this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_TAKE_NOTIFY, target, builder -> builder
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            return OperationResult.SUCCESS;

        }
    }

    @NonNull
    public OperationResult set(@NonNull OperationContext context, @NonNull Player player,
                               @NonNull ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            return this.set(context, this.userManager.getOrFetch(player), currency, amount);

        }
    }

    @NonNull
    public OperationResult set(@NonNull OperationContext context, @NonNull CoinsUser user,
                               @NonNull ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            if (!this.assertOperationsEnabled(context)) return OperationResult.FAILURE;

            OperationExecutor executor = context.getExecutor();

            if (!BalanceTransactions.set(user, currency, amount)) return OperationResult.FAILURE;
            user.markDirty();

            if (this.logger != null && context.shouldNotifyLogger()) {
                this.logger.addEntry(context, "[%s] %s set %s's balance to %s. New balance: %s"
                    .formatted(currency.getId(), executor.getName(), user.getName(), currency.format(amount), currency
                        .format(user.getBalance(currency)))
                );
            }

            if (context.shouldNotify(NotificationTarget.EXECUTOR)) {
                executor.getBukkitSender().ifPresent(sender -> {
                    this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_SET_DONE, sender, builder -> builder
                        .with(CommonPlaceholders.PLAYER_NAME, user::getName)
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            if (context.shouldNotify(NotificationTarget.USER)) {
                user.player().ifPresent(target -> {
                    this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.COMMAND_CURRENCY_SET_NOTIFY, target, builder -> builder
                        .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            return OperationResult.SUCCESS;

        }
    }

    @NonNull
    public OperationResult reset(@NonNull OperationContext context, @NonNull Player player,
                                 @NonNull ExcellentCurrency currency) {
        synchronized (UserBalance.class) {
            return this.reset(context, this.userManager.getOrFetch(player), currency);

        }
    }

    @NonNull
    public OperationResult reset(@NonNull OperationContext context, @NonNull CoinsUser user,
                                 @NonNull ExcellentCurrency currency) {
        synchronized (UserBalance.class) {
            if (!this.assertOperationsEnabled(context)) return OperationResult.FAILURE;

            OperationExecutor executor = context.getExecutor();

            if (!BalanceTransactions.set(user, currency, currency.getStartValue())) return OperationResult.FAILURE;
            user.markDirty();

            if (this.logger != null && context.shouldNotifyLogger()) {
                this.logger.addEntry(context, "[%s] %s reset %s's balance of %s to %s."
                    .formatted(currency.getId(), executor.getName(), user.getName(), currency.getName(), currency.format(
                        user.getBalance(currency)))
                );
            }

            if (context.shouldNotify(NotificationTarget.EXECUTOR)) {
                executor.getBukkitSender().ifPresent(sender -> {
                    this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_OPERATION_RESET_FEEDBACK, sender, builder -> builder
                        .with(CommonPlaceholders.PLAYER_NAME, user::getName)
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            if (context.shouldNotify(NotificationTarget.USER)) {
                user.player().ifPresent(target -> {
                    this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.CURRENCY_OPERATION_RESET_NOTIFY, target, builder -> builder
                        .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(user.getBalance(currency)))
                    ));
                });
            }

            return OperationResult.SUCCESS;

        }
    }

    public OperationResult transfer(OperationContext context, CoinsUser sender, CoinsUser receiver, ExcellentCurrency currency, double amount) {
        synchronized (UserBalance.class) {
            if (!this.assertOperationsEnabled(context)
                || !BalanceTransactions.transfer(sender, currency, receiver, currency, amount, amount)) return OperationResult.FAILURE;
            sender.markDirty();
            receiver.markDirty();
            if (this.logger != null && context.shouldNotifyLogger()) {
                this.logger.addEntry(context, "[%s] %s transferred %s from %s to %s."
                    .formatted(currency.getId(), context.getExecutor().getName(), currency.format(amount), sender.getName(), receiver.getName()));
            }
            return OperationResult.SUCCESS;

        }
    }

    public boolean send(@NonNull Player sender, @NonNull CoinsUser targetUser, @NonNull ExcellentCurrency currency,
                        double rawAmount) {
        synchronized (UserBalance.class) {
            OperationContext context = OperationContext.of(sender);

            if (!currency.isTransferAllowed() || !currency.isCommandEnabled("send") || !currency.hasPermission(sender)
                || !sender.hasPermission(Perms.currencyCommand(currency, Perms.COMMAND_CURRENCY_SEND))) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(CoreLang.ERROR_NO_PERMISSION, sender));
                return false;
            }
            if (!this.assertOperationsEnabled(context)) return false;

            if (targetUser.isHolder(sender)) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(CoreLang.COMMAND_EXECUTION_NOT_YOURSELF, sender));
                return false;
            }

            double amount = currency.floorIfNeeded(rawAmount);
            if (!Double.isFinite(amount) || amount <= 0D) return false;

            double minAmount = currency.getMinTransferAmount();
            if (minAmount > 0 && amount < minAmount) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_TOO_LOW, sender, builder -> builder
                    .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(minAmount))
                ));
                return false;
            }

            TransferTax.Quote quote;
            try {
                TransferTax tax = sender.hasPermission(Perms.taxBypass(currency)) ? TransferTax.NONE : currency.getTransferTax();
                quote = tax.quote(amount, currency.isDecimal(), sender::hasPermission);
            }
            catch (IllegalArgumentException exception) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_OPERATION_FAILED, sender));
                return false;
            }
            CoinsUser fromUser = this.userManager.getOrFetch(sender);
            if (quote.debit() > fromUser.getBalance(currency)) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_NOT_ENOUGH, sender));
                return false;
            }

            CurrencySettings settings = targetUser.getSettings(currency);
            if (!settings.isPaymentsEnabled()) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_NO_PAYMENTS, sender, builder -> builder
                    .with(CommonPlaceholders.PLAYER_NAME, targetUser::getName)
                ));
                return false;
            }

            if (!BalanceTransactions.transfer(fromUser, currency, targetUser, currency, quote.debit(), quote.credit())) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_OPERATION_FAILED, sender));
                return false;
            }
            targetUser.markDirty();
            fromUser.markDirty();

            this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_SEND_DONE_SENDER, sender, builder -> builder
                .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(amount))
                .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(fromUser.getBalance(currency)))
                .with(CommonPlaceholders.PLAYER_NAME, targetUser::getName)
            ));

            if (quote.tax() > 0) {
                this.plugin.runForSender(sender, () -> currency.sendPrefixed(Lang.CURRENCY_SEND_TAX, sender, builder -> builder
                    .with("%tax%", () -> currency.format(quote.tax()))
                    .with("%payer%", () -> currency.getTransferTax().payer().name().toLowerCase(java.util.Locale.ROOT))));
            }

            targetUser.player().ifPresent(target -> {
                this.plugin.runForSender(target, () -> currency.sendPrefixed(Lang.CURRENCY_SEND_NOTIFY, target, builder -> builder
                    .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> currency.format(quote.credit()))
                    .with(EconomyPlaceholders.GENERIC_BALANCE, () -> currency.format(targetUser.getBalance(currency)))
                    .with(CommonPlaceholders.PLAYER.resolver(sender))
                ));
            });

            if (this.logger != null) {
                this.logger.addEntry(context, "[%s] %s paid %s to %s (tax: %s, debited: %s, received: %s). New balances: %s and %s.".formatted(
                    currency.getId(),
                    sender.getName(),
                    currency.format(amount),
                    targetUser.getName(),
                    currency.format(quote.tax()),
                    currency.format(quote.debit()),
                    currency.format(quote.credit()),
                    currency.format(fromUser.getBalance(currency)),
                    currency.format(targetUser.getBalance(currency))
                ));
            }

            return true;

        }
    }

    public boolean exchange(@NonNull Player player, @NonNull ExcellentCurrency sourceCurrency,
                            @NonNull ExcellentCurrency targetCurrency, double initAmount) {
        synchronized (UserBalance.class) {
            OperationContext context = OperationContext.of(player);

            if (!this.assertOperationsEnabled(context)) return false;

            if (!sourceCurrency.hasPermission(player) || !targetCurrency.hasPermission(player)
                || !sourceCurrency.isCommandEnabled("exchange")
                || !player.hasPermission(Perms.currencyCommand(sourceCurrency, Perms.COMMAND_CURRENCY_EXCHANGE))) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(CoreLang.ERROR_NO_PERMISSION, player));
                return false;
            }
            if (!sourceCurrency.isExchangeAllowed()) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_DISABLED, player));
                return false;
            }

            double amount = sourceCurrency.floorIfNeeded(initAmount);
            if (!Double.isFinite(amount) || amount <= 0D) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LOW_AMOUNT, player));
                return false;
            }

            CoinsUser user = this.userManager.getOrFetch(player);
            if (user.getBalance(sourceCurrency) < amount) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LOW_BALANCE, player, builder -> builder
                    .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> sourceCurrency.format(amount))
                ));
                return false;
            }

            if (!sourceCurrency.canExchangeTo(targetCurrency)) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_NO_RATE, player, builder -> builder
                    .with(EconomyPlaceholders.GENERIC_NAME, targetCurrency::getName)
                ));
                return false;
            }

            double result = sourceCurrency.getExchangeResult(targetCurrency, amount);
            if (!Double.isFinite(result) || result <= 0D) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LOW_AMOUNT, player));
                return false;
            }

            double newBalance = user.getBalance(targetCurrency) + result;
            if (!targetCurrency.isUnderLimit(newBalance)) {
                this.plugin.runForSender(player, () -> targetCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LIMIT_EXCEED, player, builder -> builder
                    .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> targetCurrency.format(result))
                    .with(EconomyPlaceholders.GENERIC_MAX, () -> targetCurrency.format(targetCurrency.getMaxValue()))
                ));
                return false;
            }

            if (!BalanceTransactions.transfer(user, sourceCurrency, user, targetCurrency, amount, result)) {
                this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_OPERATION_FAILED, player));
                return false;
            }
            user.markDirty();

            this.plugin.runForSender(player, () -> sourceCurrency.sendPrefixed(Lang.CURRENCY_EXCHANGE_SUCCESS, player, builder -> builder
                .with(EconomyPlaceholders.GENERIC_BALANCE, () -> sourceCurrency.format(amount))
                .with(EconomyPlaceholders.GENERIC_AMOUNT, () -> targetCurrency.format(result))
            ));

            if (this.logger != null) {
                this.logger.addEntry(context, "[%s] %s exchanged %s for %s [%s]. New balances: %s and %s."
                    .formatted(
                        sourceCurrency.getId(),
                        user.getName(),
                        sourceCurrency.format(amount),
                        targetCurrency.format(result),
                        targetCurrency.getId(),
                        sourceCurrency.format(user.getBalance(sourceCurrency)),
                        targetCurrency.format(user.getBalance(targetCurrency))
                    )
                );
            }

            return true;

        }
    }
}
