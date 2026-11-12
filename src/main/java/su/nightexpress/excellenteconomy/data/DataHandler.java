package su.nightexpress.excellenteconomy.data;

import com.google.gson.*;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.data.postgres.*;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.data.*;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.user.data.*;
import java.util.*;

/** Storage boundary: NightCore continues handling MySQL/SQLite; PostgreSQL uses its native dialect. */
public class DataHandler extends AbstractManager<EconomyPlugin> implements UserDataSchema<CoinsUser> {
    public static final Gson GSON=new GsonBuilder().registerTypeAdapter(CurrencySettings.class,new CurrencySettingsSerializer()).create();
    private final LegacyDataHandler legacy;
    private final PostgresAccounts postgres;
    private final UserDataAccessor<CoinsUser> accessor;
    private volatile boolean synchronizationActive=true;

    public DataHandler(EconomyPlugin plugin) {
        super(plugin);
        String type=plugin.getEngineConfig().getString("Database.Type","SQLITE").toUpperCase(Locale.ROOT);
        if(Set.of("POSTGRESQL","POSTGRES","PGSQL").contains(type)) {
            legacy=null; postgres=new PostgresAccounts(PostgresSettings.read(plugin.getEngineConfig())); accessor=postgres;
        } else if(Set.of("MYSQL","SQLITE").contains(type)) {
            postgres=null; legacy=new LegacyDataHandler(plugin); accessor=new DefaultUserDataAccessor<>(legacy,legacy);
        } else throw new IllegalArgumentException("Unsupported database type; refusing a storage fallback");
    }
    @Override protected void onLoad() {
        if(legacy!=null) legacy.setup();
        else {
            postgres.initialize();
            if(plugin.getEngineConfig().getBoolean("Database.Purge.Enabled",false)) {
                int days=plugin.getEngineConfig().getInt("Database.Purge.For_Period",60);
                if(days>0) postgres.purgeBefore(System.currentTimeMillis()-java.time.Duration.ofDays(days).toMillis());
            }
            int interval=plugin.getEngineConfig().getInt("Database.PostgreSQL.Balance_Refresh_Interval",1);
            if(interval<1 || interval>300) throw new IllegalArgumentException("PostgreSQL balance refresh interval must be between 1 and 300 seconds");
            if(interval>0) addAsyncTask(()->{
                if(synchronizationActive) plugin.getAPI().userManager().synchronizeAccounts(() -> postgres.synchronize(List.copyOf(plugin.getAPI().userManager().getRepository().getLoadedByIdMap().keySet())));
            },interval);
        }
    }
    @Override protected void onShutdown() {
        if(legacy!=null) legacy.shutdown(); else postgres.close();
        DataColumns.clearCache();
    }
    public void refreshBalances() { if(postgres!=null) postgres.synchronize(List.copyOf(plugin.getAPI().userManager().getRepository().getLoadedByIdMap().keySet())); }
    private LegacyDataHandler legacySchema() {
        if(legacy==null) throw new UnsupportedOperationException("PostgreSQL uses the native account accessor, not NightCore's MySQL/SQLite SQL dialect");
        return legacy;
    }
    public su.nightexpress.nightcore.db.config.DatabaseConfig getConfig() { return legacySchema().getConfig(); }
    public su.nightexpress.nightcore.db.config.DatabaseType getDatabaseType() { return legacySchema().getDatabaseType(); }
    public String getTablePrefix() { return postgres!=null?postgres.settings().tablePrefix():legacy.getTablePrefix(); }
    public su.nightexpress.nightcore.db.connection.AbstractConnector getConnector() { return legacySchema().getConnector(); }
    @Override public su.nightexpress.nightcore.db.table.Table getUsersTable() { return legacySchema().getUsersTable(); }
    @Override public su.nightexpress.nightcore.db.column.Column<UUID> getUserIdColumn() { return DataColumns.USER_UUID; }
    @Override public su.nightexpress.nightcore.db.column.Column<String> getUserNameColumn() { return DataColumns.USER_NAME; }
    @Override public su.nightexpress.nightcore.db.statement.template.SelectStatement<CoinsUser> getUserSelectStatement() { return DataQueries.userSelect(); }
    @Override public su.nightexpress.nightcore.db.statement.template.InsertStatement<CoinsUser> getUserInsertStatement() { return DataQueries.userInsert(); }
    @Override public su.nightexpress.nightcore.db.statement.template.UpdateStatement<CoinsUser> getUserUpdateStatement() { return DataQueries.userUpdate(); }
    @Override public su.nightexpress.nightcore.db.statement.template.UpdateStatement<CoinsUser> getUserTinyUpdateStatement() { return DataQueries.userTinyUpdate(); }

    public UserDataAccessor<CoinsUser> getUserDataAccessor() { return accessor; }
    public java.util.List<su.nightexpress.excellenteconomy.tops.TopEntry> leaderboard(ExcellentCurrency currency) { return postgres.leaderboard(currency); }
    public void onCommitted(java.util.function.Consumer<Collection<CoinsUser>> callback) { if(postgres!=null) postgres.onCommitted(callback); }
    public <T> T atomic(Collection<UUID> ids,java.util.function.Function<Map<UUID,CoinsUser>,T> action,java.util.function.Predicate<T> success,T failure) {
        try { return postgres.atomic(ids,action,success); }
        catch(RuntimeException e) { plugin.error("PostgreSQL transaction rejected; no financial success was acknowledged."); return failure; }
    }
    public boolean edit(UUID uuid,ExcellentCurrency currency,java.util.function.Consumer<su.nightexpress.excellenteconomy.user.UserBalance> action) {
        if(!synchronizationActive || su.nightexpress.excellenteconomy.user.CoinsUser.balanceChangeInProgress()) return false;
        return atomic(List.of(uuid),users->users.get(uuid).tryEditBalance(currency,action),Boolean::booleanValue,false);
    }
    public boolean isPostgres() { return postgres!=null; }
    public void setSynchronizationActive(boolean active) { synchronizationActive=active; if(legacy!=null) legacy.setSynchronizationActive(active); }
    public void onCurrencyRegister(ExcellentCurrency c) { if(legacy!=null) legacy.onCurrencyRegister(c); else postgres.registerCurrency(c); }
    public void onCurrencyUnload(ExcellentCurrency c) { if(legacy!=null) legacy.onCurrencyUnload(c); else postgres.unregisterCurrency(c); }
    public void addCurrencyColumn(ExcellentCurrency c) { onCurrencyRegister(c); }
    public void resetBalances(ExcellentCurrency c) { resetBalances(Set.of(c)); }
    public void resetBalances(Collection<ExcellentCurrency> currencies) { if(legacy!=null) legacy.resetBalances(currencies); else postgres.resetBalances(currencies); }
}
