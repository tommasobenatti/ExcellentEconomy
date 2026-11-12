package su.nightexpress.excellenteconomy.data.postgres;

import com.zaxxer.hikari.*;
import org.postgresql.ds.PGSimpleDataSource;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.data.*;
import su.nightexpress.excellenteconomy.user.*;
import su.nightexpress.nightcore.user.UserInfo;
import su.nightexpress.nightcore.user.data.UserDataAccessor;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import su.nightexpress.excellenteconomy.user.data.CurrencySettings;
import com.google.gson.reflect.TypeToken;

/** JDBC failures propagate: a failed account load must never become a new starting balance. */
public final class PostgresAccounts implements UserDataAccessor<CoinsUser>, AutoCloseable {
    private final PostgresSettings settings;
    private final HikariDataSource pool;
    private final String table;
    private final Map<String,ExcellentCurrency> currencies = new LinkedHashMap<>();
    private volatile List<ExcellentCurrency> columns = List.of();
    private record Queries(List<ExcellentCurrency> fields, String select, String insert, String update) { }
    private volatile Queries queries;
    private static final java.lang.reflect.Type SETTINGS_TYPE = TypeToken.getParameterized(Map.class,String.class,CurrencySettings.class).getType();
    private volatile Consumer<CoinsUser> synchronizer;
    private volatile Consumer<Collection<CoinsUser>> committed=users->{};

    public PostgresAccounts(PostgresSettings settings) {
        this.settings=settings;
        this.table=PostgresSettings.identifier(settings.tablePrefix()+"_users");
        PGSimpleDataSource data = new PGSimpleDataSource();
        data.setServerNames(new String[]{settings.host()}); data.setPortNumbers(new int[]{settings.port()});
        data.setDatabaseName(settings.database()); data.setUser(settings.username()); data.setPassword(settings.password());
        data.setSslMode(settings.sslMode()); data.setConnectTimeout(Math.max(1,settings.connectionTimeoutMs()/1000));
        data.setSocketTimeout(settings.queryTimeoutSeconds()+2);
        data.setOptions("-c statement_timeout="+(settings.queryTimeoutSeconds()*1000)+" -c lock_timeout=2000");
        data.setReWriteBatchedInserts(true); data.setPreparedStatementCacheQueries(64); data.setPreparedStatementCacheSizeMiB(1);
        HikariConfig config=new HikariConfig(); config.setDataSource(data);
        config.setMaximumPoolSize(settings.maximumPoolSize()); config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.connectionTimeoutMs()); config.setValidationTimeout(Math.min(1000,settings.connectionTimeoutMs()));
        config.setPoolName("ExcellentEconomy-PostgreSQL"); config.setInitializationFailTimeout(1);
        pool=new HikariDataSource(config);
        rebuildSql();
    }

    public void initialize() {
        execute("CREATE TABLE IF NOT EXISTS "+table+" (uuid UUID PRIMARY KEY, name VARCHAR(32) NOT NULL, last_seen BIGINT NOT NULL, balance_version BIGINT NOT NULL DEFAULT 0, settings JSONB NOT NULL DEFAULT '{}'::jsonb, \"hiddenFromTops\" BOOLEAN NOT NULL DEFAULT false)");
        execute("ALTER TABLE "+table+" ADD COLUMN IF NOT EXISTS balance_version BIGINT NOT NULL DEFAULT 0");
        execute("CREATE INDEX IF NOT EXISTS "+PostgresSettings.identifier(settings.tablePrefix()+"_name_idx")+" ON "+table+" (lower(name))");
    }

    public synchronized void registerCurrency(ExcellentCurrency currency) {
        String column=currency.getColumnName(); PostgresSettings.identifier(column);
        if(Set.of("uuid","name","last_seen","settings","hiddenfromtops","balance_version").contains(column.toLowerCase(Locale.ROOT))
            || currencies.values().stream().anyMatch(c->!c.getId().equals(currency.getId()) && c.getColumnName().equalsIgnoreCase(column)))
            throw new IllegalArgumentException("Currency column conflicts with another account field");
        double start=currency.getStartValue(); if(!Double.isFinite(start)||start<0) throw new IllegalArgumentException("Invalid starting value");
        execute("ALTER TABLE "+table+" ADD COLUMN IF NOT EXISTS "+PostgresSettings.identifier(column)+" DOUBLE PRECISION NOT NULL DEFAULT "+start+" CHECK ("+PostgresSettings.identifier(column)+">=0 AND "+PostgresSettings.identifier(column)+"<'Infinity'::float8)");
        currencies.put(currency.getId(),currency); DataColumns.forCurrency(currency); rebuildSql();
    }
    public synchronized void unregisterCurrency(ExcellentCurrency currency) {
        currencies.remove(currency.getId()); DataColumns.uncacheCurrency(currency); rebuildSql();
    }
    private void rebuildSql() {
        columns=List.copyOf(currencies.values());
        String names="uuid,name,last_seen,settings,\"hiddenFromTops\"";
        String values="?,?,?,?::jsonb,?";
        String update="name=?,last_seen=?,settings=?::jsonb,\"hiddenFromTops\"=?";
        for(var currency:columns) { String col=PostgresSettings.identifier(currency.getColumnName()); names+=","+col; values+=",?"; update+=","+col+"=?"; }
        queries=new Queries(columns,"SELECT "+names+",balance_version FROM "+table,
            "INSERT INTO "+table+" ("+names+") VALUES ("+values+") ON CONFLICT (uuid) DO NOTHING",
            "UPDATE "+table+" SET name=CASE WHEN last_seen<=? THEN ? ELSE name END,last_seen=GREATEST(last_seen,?),settings=settings || ?::jsonb,\"hiddenFromTops\"=CASE WHEN ? THEN ? ELSE \"hiddenFromTops\" END WHERE uuid=?");
    }
    private PreparedStatement prepare(Connection connection,String sql) throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);
        statement.setQueryTimeout(settings.queryTimeoutSeconds());
        return statement;
    }
    private static IllegalStateException failure(SQLException e) { return new IllegalStateException("PostgreSQL operation failed (SQLSTATE "+e.getSQLState()+")",e); }
    private void execute(String sql) {
        try(Connection c=pool.getConnection(); Statement s=c.createStatement()) { s.setQueryTimeout(settings.queryTimeoutSeconds()); s.execute(sql); }
        catch(SQLException e) { throw failure(e); }
    }
    private List<CoinsUser> query(String suffix, Object parameter) {
        List<CoinsUser> result=new ArrayList<>();
        Queries plan=queries;
        try(Connection c=pool.getConnection(); PreparedStatement s=prepare(c,plan.select()+suffix)) {
            if(parameter!=null) s.setObject(1,parameter);
            try(ResultSet rs=s.executeQuery()) { while(rs.next()) {
                CoinsUser user=read(rs,plan.fields());
                if(user==null) throw new SQLException("Invalid persisted account");
                result.add(user);
            }}
        } catch(SQLException e) { throw failure(e); }
        return result;
    }
    private CoinsUser read(ResultSet rs,List<ExcellentCurrency> fields) throws SQLException {
        Map<String,Double> balances=new HashMap<>();
        for(var field:fields) {
            double value=rs.getDouble(field.getColumnName());
            if(rs.wasNull() || !Double.isFinite(value) || value<0) throw new SQLException("Invalid persisted balance");
            balances.put(field.getId(),value);
        }
        Map<String,CurrencySettings> preferences=DataHandler.GSON.fromJson(rs.getString("settings"),SETTINGS_TYPE);
        var user=new CoinsUser(rs.getObject("uuid",UUID.class),rs.getString("name"),new UserBalance(balances),
            preferences==null?Map.of():preferences,rs.getLong("last_seen"),rs.getBoolean("hiddenFromTops"));
        user.balanceVersion(rs.getLong("balance_version"));
        return user;
    }

    @Override public List<CoinsUser> loadAll() { return query("",null); }
    @Override public Optional<CoinsUser> loadByName(String name) { return query(" WHERE lower(name)=lower(?) ORDER BY last_seen DESC LIMIT 1",name).stream().findFirst(); }
    @Override public Optional<CoinsUser> loadById(UUID uuid) { return query(" WHERE uuid=?",uuid).stream().findFirst(); }
    @Override public List<UserInfo> loadProfiles() {
        List<UserInfo> result=new ArrayList<>();
        try(Connection c=pool.getConnection(); PreparedStatement s=prepare(c,"SELECT uuid,name FROM "+table); ResultSet rs=s.executeQuery()) {
            while(rs.next()) result.add(new UserInfo(rs.getObject(1,UUID.class),rs.getString(2)));
        } catch(SQLException e) { throw failure(e); }
        return result;
    }
    @Override public boolean isExists(String name) { return exists("lower(name)=lower(?)",name); }
    @Override public boolean isExists(UUID uuid) { return exists("uuid=?",uuid); }
    private boolean exists(String predicate,Object value) {
        try(Connection c=pool.getConnection(); PreparedStatement s=prepare(c,"SELECT 1 FROM "+table+" WHERE "+predicate+" LIMIT 1")) {
            s.setObject(1,value); try(ResultSet rs=s.executeQuery()) { return rs.next(); }
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public void insert(CoinsUser user) { insert(List.of(user)); }
    @Override public void insert(Collection<CoinsUser> users) { write(users,true); }
    @Override public void update(CoinsUser user) { update(List.of(user)); }
    @Override public void update(Collection<CoinsUser> users) { write(users,false); }
    @Override public void tinyUpdate(CoinsUser user) { tinyUpdate(List.of(user)); }
    @Override public void tinyUpdate(Collection<CoinsUser> users) {
        if(users.isEmpty()) return;
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement s=prepare(c,"UPDATE "+table+" SET name=?,last_seen=? WHERE uuid=?")) {
                for(var user:users) { s.setString(1,user.getName()); s.setLong(2,user.getLastSeen()); s.setObject(3,user.getId()); s.addBatch(); }
                s.executeBatch(); c.commit();
            } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        } catch(SQLException e) { throw failure(e); }
    }
    private synchronized void write(Collection<CoinsUser> users,boolean insert) {
        if(users.isEmpty()) return;
        Queries plan=queries;
        List<ExcellentCurrency> fields=plan.fields();
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement s=prepare(c,insert?plan.insert():plan.update())) {
                int pending=0;
                for(var user:users.stream().sorted(Comparator.comparing(u->u.getId().toString())).toList()) {
                    int i=1;
                    if(insert) {
                        s.setObject(i++,user.getId());
                        s.setString(i++,user.getName()); s.setLong(i++,user.getLastSeen());
                        s.setString(i++,DataHandler.GSON.toJson(user.getSettingsMap())); s.setBoolean(i++,user.isHiddenFromTops());
                        for(var field:fields) { double v=user.getBalance().get(field.getId()); if(!Double.isFinite(v)||v<0) throw new IllegalArgumentException("Invalid persisted balance"); s.setDouble(i++,v); }
                    } else {
                        s.setLong(i++,user.getLastSeen()); s.setString(i++,user.getName()); s.setLong(i++,user.getLastSeen());
                        s.setString(i++,DataHandler.GSON.toJson(user.changedSettings())); s.setBoolean(i++,user.hiddenChanged()); s.setBoolean(i++,user.isHiddenFromTops());
                        s.setObject(i,user.getId());
                    }
                    s.addBatch(); pending++;
                    if(pending==settings.batchSize()) { checkBatch(s.executeBatch(),insert); pending=0; }
                }
                if(pending>0) checkBatch(s.executeBatch(),insert);
                c.commit();
            } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        } catch(SQLException e) { throw failure(e); }
    }
    private void checkBatch(int[] counts,boolean insert) {
        for(int count:counts) if(count==Statement.EXECUTE_FAILED || (!insert && count==0)) throw new IllegalStateException("Account was not saved; the transaction was rolled back");
    }
    @Override public void deleteByName(String name) { delete("lower(name)=lower(?)",name); }
    @Override public void deleteById(UUID id) { delete("uuid=?",id); }
    private void delete(String predicate,Object value) {
        try(Connection c=pool.getConnection(); PreparedStatement s=prepare(c,"DELETE FROM "+table+" WHERE "+predicate)) { s.setObject(1,value); s.executeUpdate(); }
        catch(SQLException e) { throw failure(e); }
    }
    public synchronized void resetBalances(Collection<ExcellentCurrency> fields) {
        if(fields.isEmpty()) return;
        StringJoiner assignments=new StringJoiner(",");
        for(var f:fields) { if(!currencies.containsKey(f.getId())) throw new IllegalArgumentException("Unknown currency"); assignments.add(PostgresSettings.identifier(f.getColumnName())+"=?"); }
        try(Connection c=pool.getConnection(); PreparedStatement s=prepare(c,"UPDATE "+table+" SET "+assignments+",balance_version=balance_version+1")) { int i=1; for(var f:fields) s.setDouble(i++,f.getStartValue()); s.executeUpdate(); }
        catch(SQLException e) { throw failure(e); }
    }
    @Override public void addSynchronization(Consumer<CoinsUser> consumer) { synchronizer=consumer; }
    public void synchronize(Collection<UUID> ids) {
        Consumer<CoinsUser> consumer=synchronizer; if(consumer==null || ids.isEmpty()) return;
        Queries plan=queries;
        try(Connection c=pool.getConnection(); PreparedStatement s=prepare(c,plan.select()+" WHERE uuid=ANY(?)")) {
            Array values=c.createArrayOf("uuid",ids.toArray());
            try { s.setArray(1,values); try(ResultSet rs=s.executeQuery()) { while(rs.next()) { CoinsUser user=read(rs,plan.fields()); if(user==null) throw new SQLException("Invalid persisted account"); consumer.accept(user); }} }
            finally { values.free(); }
        } catch(SQLException e) { throw failure(e); }
    }
    public PostgresSettings settings() { return settings; }
    public void onCommitted(Consumer<Collection<CoinsUser>> consumer) { committed=consumer; }
    public <T> T atomic(Collection<UUID> ids, java.util.function.Function<Map<UUID,CoinsUser>,T> operation, java.util.function.Predicate<T> success) {
        if(AccountTransaction.active() || Thread.holdsLock(UserBalance.class)) throw new IllegalStateException("Nested balance transaction rejected");
        Queries plan=queries;
        Map<UUID,CoinsUser> accounts=new LinkedHashMap<>();
        AccountTransaction transaction=new AccountTransaction(this,accounts);
        T result;
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement select=prepare(c,plan.select()+" WHERE uuid=ANY(?) ORDER BY uuid FOR UPDATE")) {
                    Array keys=c.createArrayOf("uuid",new HashSet<>(ids).toArray());
                    try { select.setArray(1,keys); try(ResultSet rs=select.executeQuery()) { while(rs.next()) { var user=read(rs,plan.fields()); accounts.put(user.getId(),user); }} }
                    finally { keys.free(); }
                }
                if(accounts.size()!=new HashSet<>(ids).size()) throw new IllegalStateException("Missing transaction account");
                AccountTransaction.enter(transaction);
                try { result=operation.apply(accounts); } finally { AccountTransaction.leave(); }
                if(success.test(result)) {
                    StringJoiner assignments=new StringJoiner(",");
                    plan.fields().forEach(field -> assignments.add(PostgresSettings.identifier(field.getColumnName())+"=?"));
                    try(PreparedStatement update=prepare(c,"UPDATE "+table+" SET "+assignments+",balance_version=balance_version+1 WHERE uuid=?")) {
                        for(var user:accounts.values()) {
                            int index=1;
                            for(var field:plan.fields()) { double amount=user.getBalance().get(field.getId()); if(!Double.isFinite(amount)||amount<0) throw new IllegalStateException("Invalid transaction balance"); update.setDouble(index++,amount); }
                            update.setObject(index,user.getId()); update.addBatch();
                        }
                        checkBatch(update.executeBatch(),false);
                    }
                    c.commit();
                    accounts.values().forEach(user -> user.balanceVersion(user.balanceVersion()+1));
                } else { c.rollback(); accounts.clear(); }
            } catch(SQLException|RuntimeException e) { AccountTransaction.leave(); c.rollback(); throw e; }
        } catch(SQLException e) { throw failure(e); }
        if(!accounts.isEmpty()) {
            try { committed.accept(accounts.values()); }
            catch(RuntimeException e) { java.util.logging.Logger.getLogger("ExcellentEconomy").warning("A committed balance could not refresh the local cache"); }
        }
        for(var effect:accounts.isEmpty()?transaction.failureEffects:transaction.effects) {
            try { effect.run(); }
            catch(RuntimeException e) { java.util.logging.Logger.getLogger("ExcellentEconomy").warning("A post-transaction notification failed"); }
        }
        return result;
    }

    public List<su.nightexpress.excellenteconomy.tops.TopEntry> leaderboard(ExcellentCurrency currency) {
        String column=PostgresSettings.identifier(currency.getColumnName());
        List<su.nightexpress.excellenteconomy.tops.TopEntry> entries=new ArrayList<>();
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement s=prepare(c,"SELECT uuid,name,"+column+" FROM "+table+" WHERE NOT \"hiddenFromTops\" ORDER BY "+column+" DESC,uuid")) {
                s.setFetchSize(256);
                try(ResultSet rs=s.executeQuery()) { while(rs.next()) entries.add(new su.nightexpress.excellenteconomy.tops.TopEntry(entries.size()+1,rs.getString("name"),rs.getObject("uuid",UUID.class),rs.getDouble(currency.getColumnName()))); }
                c.commit();
            } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        } catch(SQLException e) { throw failure(e); }
        return entries;
    }
    public void purgeBefore(long timestamp) { delete("last_seen<?",timestamp); }

    @Override public void close() { pool.close(); }
    public int maximumPoolSize() { return settings.maximumPoolSize(); }
}
