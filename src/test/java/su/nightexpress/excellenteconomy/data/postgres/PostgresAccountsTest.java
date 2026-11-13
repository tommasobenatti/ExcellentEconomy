package su.nightexpress.excellenteconomy.data.postgres;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.data.DataColumns;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.nightcore.config.FileConfig;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("postgres")
class PostgresAccountsTest {
    @TempDir Path directory;
    private PostgresAccounts store;
    private String prefix;

    @BeforeEach void open() {
        TestServer.initialize();
        String url = Objects.requireNonNull(System.getenv("ECONOMY_TEST_PG_URL"));
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"));
        FileConfig config = FileConfig.load(directory.resolve("engine.yml"));
        prefix = "ee_test_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
        config.set("Database.Table_Prefix", prefix);
        config.set("Database.PostgreSQL.Host", "127.0.0.1");
        config.set("Database.PostgreSQL.Port", Integer.parseInt(url.split(":")[3].split("/")[0]));
        config.set("Database.PostgreSQL.Database", "ee_test");
        config.set("Database.PostgreSQL.Username", "ee_test");
        config.set("Database.PostgreSQL.Password", System.getenv("ECONOMY_TEST_PG_PASSWORD"));
        config.set("Database.PostgreSQL.SSL_Mode", "disable");
        store = new PostgresAccounts(PostgresSettings.read(config));
        store.initialize();
        for (String id : List.of("money","coins","souls")) {
            var currency = TestServer.currency(id);
            when(currency.getColumnName()).thenReturn(id);
            when(currency.getStartValue()).thenReturn(id.equals("money") ? 1000D : 0D);
            store.registerCurrency(currency);
        }
    }

    @AfterEach void close() throws Exception {
        if (store != null) {
            try (Connection c = connection(); Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS \"" + prefix + "_users\"");
            } finally { store.close(); DataColumns.clearCache(); }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(System.getenv("ECONOMY_TEST_PG_URL"), "ee_test", System.getenv("ECONOMY_TEST_PG_PASSWORD"));
    }

    @Test void roundTripsAccountsAndNeverOverwritesAnExistingAccountOnCreate() throws Exception {
        var money = TestServer.currency("money");
        CoinsUser user = TestServer.user("MixedCase", money, 1250.25);
        user.getBalance().set("coins", 50); user.getBalance().set("souls", 6);
        store.insert(user);
        store.insert(TestServer.user("Other", money, 1));
        assertEquals(user.getId(), store.loadByName("mixedCASE").orElseThrow().getId());
        assertEquals(1250.25, store.loadById(user.getId()).orElseThrow().getBalance(money));
        var duplicate = new CoinsUser(user.getId(), "Ignored", new su.nightexpress.excellenteconomy.user.UserBalance(Map.of("money",0D)), Map.of(), 0, false);
        store.insert(duplicate);
        assertEquals(1250.25, store.loadById(user.getId()).orElseThrow().getBalance(money));
        store.atomic(List.of(user.getId()),rows -> su.nightexpress.excellenteconomy.user.BalanceTransactions.set(rows.get(user.getId()),money,1200.5),Boolean::booleanValue);
        assertEquals(1200.5, store.loadById(user.getId()).orElseThrow().getBalance(money));
        store.resetBalances(List.of(currency("souls", 0)));
        assertEquals(0, store.loadById(user.getId()).orElseThrow().getBalance().get("souls"));
        assertEquals(50, store.loadById(user.getId()).orElseThrow().getBalance().get("coins"));
        store.deleteById(user.getId());
        assertTrue(store.loadById(user.getId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.update(user));
        assertTrue(store.loadById(user.getId()).isEmpty());
        try (Connection c=connection(); PreparedStatement s=c.prepareStatement("SELECT indexdef FROM pg_indexes WHERE tablename=?")) {
            s.setString(1,prefix+"_users");
            try (ResultSet rs=s.executeQuery()) { var indexes=new ArrayList<String>(); while(rs.next()) indexes.add(rs.getString(1)); assertTrue(indexes.stream().anyMatch(x->x.contains("lower"))); assertTrue(indexes.stream().anyMatch(x->x.contains("uuid"))); }
        }
    }

    @Test void rejectsUnsafeIdentifiersAndRollsBackFailedBatches() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.registerCurrency(currency("bad\"; DROP TABLE x",0)));
        assertThrows(IllegalArgumentException.class, () -> store.registerCurrency(currency("balance_version",0)));
        assertThrows(IllegalArgumentException.class, () -> store.registerCurrency(currency("invalid_start",-1)));
        assertThrows(IllegalArgumentException.class, () -> store.resetBalances(List.of(currency("missing",0))));
        var money=TestServer.currency("money");
        var first=TestServer.user("First",money,10); var missing=TestServer.user("Missing",money,0);
        store.insert(first); first.getBalance().set("money",20);
        assertThrows(IllegalStateException.class, () -> store.update(List.of(first,missing)));
        assertEquals(10,store.loadById(first.getId()).orElseThrow().getBalance(money));
    }

    @Test void twoBackendsCannotSpendTheSameMoneyAndMetadataNeverRestoresOldBalances() throws Exception {
        var money=TestServer.currency("money");
        var user=TestServer.user("shared",money,100);
        store.insert(user);
        var stale=store.loadById(user.getId()).orElseThrow();
        // Reuse the same settings from the owning store through an explicit second connection pool.
        var second=new PostgresAccounts(store.settings());
        for(String id:List.of("money","coins","souls")) second.registerCurrency(currency(id,id.equals("money")?1000:0));
        assertEquals(100,second.loadById(user.getId()).orElseThrow().getBalance(money));
        try(second; var threads=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var work=new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int n=0;n<20;n++) { var backend=n%2==0?store:second;
                work.add(()->backend.atomic(List.of(user.getId()),rows->su.nightexpress.excellenteconomy.user.BalanceTransactions.withdraw(rows.get(user.getId()),money,10),Boolean::booleanValue));
            }
            int successes=0; for(var result:threads.invokeAll(work)) if(result.get()) successes++;
            assertEquals(10,successes);
            assertEquals(0,store.loadById(user.getId()).orElseThrow().getBalance(money));
            store.update(stale);
            assertEquals(0,store.loadById(user.getId()).orElseThrow().getBalance(money));
        }
    }

    @Test @SuppressWarnings({"rawtypes","unchecked"})
    void nativeVaultUsesAtomicBackendEvenWithStaleCachedAccounts() throws Exception {
        var money=TestServer.currency("money"); when(money.isPrimary()).thenReturn(true);
        var from=TestServer.user("from",money,100); var to=TestServer.user("to",money,0);
        store.insert(List.of(from,to));
        var registry=new su.nightexpress.excellenteconomy.currency.CurrencyRegistry(); registry.add(money);
        var users=mock(su.nightexpress.excellenteconomy.user.UserManager.class);
        when(users.findById(from.getId())).thenReturn(Optional.of(from)); when(users.findById(to.getId())).thenReturn(Optional.of(to));
        var data=mock(su.nightexpress.excellenteconomy.data.DataHandler.class); when(data.isPostgres()).thenReturn(true);
        when(data.atomic(anyCollection(),any(),any(),any())).thenAnswer(call->store.atomic(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        var manager=new su.nightexpress.excellenteconomy.currency.CurrencyManager(mock(su.nightexpress.excellenteconomy.EconomyPlugin.class),registry,mock(su.nightexpress.excellenteconomy.command.CommandManager.class),data,users);
        var api=mock(su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI.class);
        when(api.currencyRegistry()).thenReturn(registry); when(api.currencyManager()).thenReturn(manager); when(api.userManager()).thenReturn(users);
        var vault=new su.nightexpress.excellenteconomy.hook.VaultUnlockedEconomy(api);
        assertEquals(net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS,vault.transfer("test",from.getId(),to.getId(),new java.math.BigDecimal("60")).type);
        assertEquals(net.milkbowl.vault2.economy.EconomyResponse.ResponseType.FAILURE,vault.transfer("test",from.getId(),to.getId(),new java.math.BigDecimal("60")).type);
        assertEquals(40,store.loadById(from.getId()).orElseThrow().getBalance(money));
        assertEquals(60,store.loadById(to.getId()).orElseThrow().getBalance(money));
    }

    @Test void cancelledBalanceAndFailedNotificationCannotLieAboutCommit() throws Exception {
        var money=TestServer.currency("money"); var user=TestServer.user("cancel",money,100); store.insert(user);
        doAnswer(call -> { ((su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent)call.getArgument(0)).setCancelled(true); return null; })
            .when(TestServer.PLUGINS).callEvent(any(su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent.class));
        try { assertFalse(store.<Boolean>atomic(List.of(user.getId()),rows->su.nightexpress.excellenteconomy.user.BalanceTransactions.withdraw(rows.get(user.getId()),money,10),Boolean::booleanValue)); }
        finally { reset(TestServer.PLUGINS); }
        assertEquals(100,store.loadById(user.getId()).orElseThrow().getBalance(money));
        store.onCommitted(accounts->{ throw new IllegalStateException("notification"); });
        assertTrue(store.<Boolean>atomic(List.of(user.getId()),rows->su.nightexpress.excellenteconomy.user.BalanceTransactions.withdraw(rows.get(user.getId()),money,10),Boolean::booleanValue));
        assertEquals(90,store.loadById(user.getId()).orElseThrow().getBalance(money));
    }

    @Test void rollbackDiscardsSuccessEffectsAndRejectsUnselectedCachedObjects() throws Exception {
        var money=TestServer.currency("money"); var user=TestServer.user("effects",money,100); store.insert(user);
        var effects=new ArrayList<String>();
        boolean accepted=store.atomic(List.of(user.getId()),rows->{
            assertFalse(user.tryEditBalance(money,balance->balance.set("money",999)));
            assertTrue(su.nightexpress.excellenteconomy.user.BalanceTransactions.deposit(rows.get(user.getId()),money,5));
            AccountTransaction.defer(()->effects.add("success"));
            AccountTransaction.feedback(()->effects.add("failure"));
            return false;
        },Boolean::booleanValue);
        assertFalse(accepted); assertEquals(List.of("failure"),effects);
        assertEquals(100,store.loadById(user.getId()).orElseThrow().getBalance(money));
        assertEquals(100,user.getBalance(money));
    }

    @Test void indexedLookupsRemainBoundedWithThirtyThousandAccounts() throws Exception {
        try(Connection c=connection(); Statement statement=c.createStatement()) {
            statement.execute("INSERT INTO \""+prefix+"_users\" (uuid,name,last_seen) SELECT gen_random_uuid(),'bench_'||n,0 FROM generate_series(1,30000) n");
            statement.execute("ANALYZE \""+prefix+"_users\"");
            try(ResultSet rs=statement.executeQuery("EXPLAIN (ANALYZE,FORMAT JSON) SELECT uuid FROM \""+prefix+"_users\" WHERE lower(name)=lower('BENCH_15000')")) {
                assertTrue(rs.next()); assertTrue(rs.getString(1).contains(prefix+"_name_idx"));
            }
        }
        for(int n=0;n<30;n++) assertTrue(store.loadByName("BENCH_15000").isPresent());
        long[] samples=new long[100];
        for(int n=0;n<samples.length;n++) { long start=System.nanoTime(); assertTrue(store.loadByName("BENCH_15000").isPresent()); samples[n]=System.nanoTime()-start; }
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT,"PostgreSQL 30000-account indexed lookup: p50=%.3f ms, p95=%.3f ms; pool max=%d%n",samples[50]/1e6,samples[95]/1e6,store.maximumPoolSize());
    }

    @Test void atomicWritesHaveBoundedLocalLatency() {
        var money=TestServer.currency("money"); var user=TestServer.user("latency",money,100); store.insert(user);
        long[] samples=new long[100];
        for(int n=0;n<samples.length;n++) {
            long start=System.nanoTime();
            boolean ok=store.atomic(List.of(user.getId()),rows->su.nightexpress.excellenteconomy.user.BalanceTransactions.deposit(rows.get(user.getId()),money,1),Boolean::booleanValue);
            assertTrue(ok); samples[n]=System.nanoTime()-start;
        }
        assertEquals(200,store.loadById(user.getId()).orElseThrow().getBalance(money));
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT,"PostgreSQL durable atomic write: p50=%.3f ms, p95=%.3f ms%n",samples[50]/1e6,samples[95]/1e6);
    }

    @Test void profilesPreferencesSynchronizationAndRankingUseNativeQueries() throws Exception {
        var money=currency("money",1000); var a=TestServer.user("visible",money,50); var b=TestServer.user("hidden",money,200);
        b.setHiddenFromTops(true); store.insert(List.of(a,b));
        assertTrue(store.isExists(a.getId())); assertTrue(store.isExists("VISIBLE"));
        assertEquals(2,store.loadProfiles().size()); assertEquals(2,store.loadAll().size());
        a.getSettings(money).setPaymentsEnabled(false); a.setLastSeen(System.currentTimeMillis()+10000); a.setName("renamed");
        store.update(a); store.tinyUpdate(a);
        assertFalse(store.loadByName("RENAMED").orElseThrow().getSettings(money).isPaymentsEnabled());
        var fetched=new ArrayList<CoinsUser>(); store.addSynchronization(fetched::add);
        store.synchronize(List.of(a.getId(),b.getId())); assertEquals(2,fetched.size());
        store.synchronize(List.of());
        try(var profiles=mockStatic(su.nightexpress.nightcore.util.profile.PlayerProfiles.class)) {
            profiles.when(()->su.nightexpress.nightcore.util.profile.PlayerProfiles.createProfile(any(UUID.class),anyString()))
                .thenReturn(mock(su.nightexpress.nightcore.util.profile.CachedProfile.class));
            var rank=store.leaderboard(money); assertEquals(1,rank.size()); assertEquals(a.getId(),rank.getFirst().getPlayerId());
        }
        store.purgeBefore(System.currentTimeMillis()); assertTrue(store.loadById(b.getId()).isEmpty());
        assertTrue(store.loadById(a.getId()).isPresent()); store.deleteByName("RENAMED"); assertTrue(store.loadAll().isEmpty());
        store.update(List.of()); store.insert(List.of()); store.tinyUpdate(List.of()); store.resetBalances(List.of());
    }

    private su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency currency(String column, double start) {
        var c=TestServer.currency(column); when(c.getColumnName()).thenReturn(column); when(c.getStartValue()).thenReturn(start); return c;
    }
}
