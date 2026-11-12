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
        user.getBalance().set("money", 1200.5); store.update(user);
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
        var money=TestServer.currency("money");
        var first=TestServer.user("First",money,10); var missing=TestServer.user("Missing",money,0);
        store.insert(first); first.getBalance().set("money",20);
        assertThrows(IllegalStateException.class, () -> store.update(List.of(first,missing)));
        assertEquals(10,store.loadById(first.getId()).orElseThrow().getBalance(money));
    }

    private su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency currency(String column, double start) {
        var c=TestServer.currency(column); when(c.getColumnName()).thenReturn(column); when(c.getStartValue()).thenReturn(start); return c;
    }
}
