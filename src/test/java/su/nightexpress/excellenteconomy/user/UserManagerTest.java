package su.nightexpress.excellenteconomy.user;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.nightcore.user.UserInfo;
import su.nightexpress.nightcore.user.data.DefaultUserDataAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserManagerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void missingRewardTargetIsLoggedWithoutCreatingAnAccountOrRetrying() throws Exception {
        TestServer.initialize();
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        CommandSender console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
            .when(plugin).runForSender(eq(console), any(Runnable.class));
        try (MockedConstruction<DefaultUserDataAccessor> accessors = mockConstruction(DefaultUserDataAccessor.class, (accessor, context) -> {
            when(accessor.loadByName(anyString())).thenReturn(Optional.empty());
        })) {
            UserManager users = new UserManager(plugin, new CurrencyRegistry(), null);
            CountDownLatch completed = new CountDownLatch(1);
            var result = new java.util.concurrent.atomic.AtomicReference<Optional<CoinsUser>>();
            String target = "UnknownMob\nname";
            users.withUser(console, target, user -> { result.set(user); completed.countDown(); });
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(result.get().isEmpty());
            verify(plugin).warn("Economy account not found: target=\"UnknownMob\\nname\", sender=\"CONSOLE\"");
            verify(accessors.constructed().getFirst(), times(1)).loadByName(target);
            verify(accessors.constructed().getFirst(), never()).insert(any(CoinsUser.class));
            verify(accessors.constructed().getFirst(), never()).update(any(CoinsUser.class));
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managesOfflineAccountsAndKeepsColdLookupsCanonical() throws Exception {
        TestServer.initialize();
        var currency = TestServer.currency("money");
        when(currency.getStartValue()).thenReturn(25D);
        var registry = new CurrencyRegistry();
        registry.add(currency);
        Map<UUID, CoinsUser> stored = new HashMap<>();
        try (MockedConstruction<DefaultUserDataAccessor> accessors = mockConstruction(DefaultUserDataAccessor.class, (accessor, context) -> {
            when(accessor.loadById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))).map(UserManagerTest::copy));
            when(accessor.loadByName(anyString())).thenAnswer(call -> stored.values().stream()
                .filter(user -> user.getName().equalsIgnoreCase(call.getArgument(0))).findFirst().map(UserManagerTest::copy));
            when(accessor.loadProfiles()).thenAnswer(call -> stored.values().stream().map(user -> new UserInfo(user.getId(), user.getName())).toList());
            doAnswer(call -> { CoinsUser user = call.getArgument(0); stored.put(user.getId(), copy(user)); return null; }).when(accessor).insert(any(CoinsUser.class));
            doAnswer(call -> { CoinsUser user = call.getArgument(0); stored.put(user.getId(), copy(user)); return null; }).when(accessor).update(any(CoinsUser.class));
            doAnswer(call -> { stored.remove(call.getArgument(0)); return null; }).when(accessor).deleteById(any(UUID.class));
        })) {
            UserManager users = new UserManager(mock(EconomyPlugin.class), registry, null);
            UUID uuid = UUID.randomUUID();
            assertFalse(users.createAccount(uuid, ""));
            assertFalse(users.createAccount(uuid, "x".repeat(65)));
            assertFalse(users.createAccount(uuid, "x".repeat(33)));
            assertTrue(users.createAccount(uuid, "PlayerName"));
            assertEquals(25, users.findById(uuid).orElseThrow().getBalance(currency));
            assertTrue(users.createAccount(uuid, "IgnoredExistingName"));
            assertTrue(users.renameAccount(uuid, "Renamed"));
            assertFalse(users.renameAccount(uuid, "bad\nname"));
            assertEquals("Renamed", users.getAccountNames().get(uuid));
            assertTrue(users.findByName("PlayerName").isEmpty());

            when(Bukkit.getPlayer(uuid)).thenReturn(mock(Player.class));
            try {
                assertFalse(users.renameAccount(uuid, "AnotherName"));
                assertFalse(users.deleteAccount(uuid));
            }
            finally { when(Bukkit.getPlayer(uuid)).thenReturn(null); }

            UserManager cold = new UserManager(mock(EconomyPlugin.class), registry, null);
            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                var calls = new ArrayList<Callable<CoinsUser>>();
                for (int i = 0; i < 8; i++) calls.add(() -> cold.findById(uuid).orElseThrow());
                CoinsUser first = null;
                for (Future<CoinsUser> call : pool.invokeAll(calls)) {
                    CoinsUser user = call.get();
                    if (first == null) first = user;
                    else assertSame(first, user);
                }
            }
            assertTrue(users.deleteAccount(uuid));
            assertFalse(users.deleteAccount(uuid));
            assertFalse(stored.containsKey(uuid));
            assertTrue(users.findById(uuid).isEmpty());
            assertTrue(users.getAccountNames().isEmpty());
        }
    }

    private static CoinsUser copy(CoinsUser user) {
        return new CoinsUser(user.getId(), user.getName(), new UserBalance(user.getBalance().getBalanceMap()), user.getSettingsMap(), user.getLastSeen(), user.isHiddenFromTops());
    }
}
