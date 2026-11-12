package su.nightexpress.excellenteconomy.user;

import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.nightcore.user.data.UserDataAccessor;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavePipelineTest {
    @Test @SuppressWarnings("unchecked")
    void failedSaveRetainsDirtyDataAndAConcurrentEditIsNotCleared() {
        TestServer.initialize();
        var store=(UserDataAccessor<CoinsUser>)mock(UserDataAccessor.class);
        var data=mock(DataHandler.class); when(data.getUserDataAccessor()).thenReturn(store);
        var users=new UserManager(mock(EconomyPlugin.class),new CurrencyRegistry(),data);
        var money=TestServer.currency("money"); var user=TestServer.user("player",money,10);
        users.cachePermanent(user); user.addBalance(money,5);
        doThrow(new IllegalStateException("offline")).when(store).update(anyCollection());
        users.saveDirty(); assertTrue(user.isDirty()); assertEquals(15,user.getBalance(money));
        var saved=new ArrayList<Double>();
        doAnswer(call -> {
            assertFalse(Thread.holdsLock(UserBalance.class),"JDBC must not hold the shared balance lock");
            var snapshot=((Collection<CoinsUser>)call.getArgument(0)).iterator().next();
            saved.add(snapshot.getBalance(money));
            user.addBalance(money,2);
            assertEquals(15,snapshot.getBalance(money));
            return null;
        }).when(store).update(anyCollection());
        users.saveDirty(); assertTrue(user.isDirty()); assertEquals(17,user.getBalance(money));
        doAnswer(call -> { saved.add(((Collection<CoinsUser>)call.getArgument(0)).iterator().next().getBalance(money)); return null; }).when(store).update(anyCollection());
        users.saveDirty(); assertFalse(user.isDirty()); assertEquals(List.of(15D,17D),saved);
    }

    @Test @SuppressWarnings("unchecked")
    void slowColdLookupDoesNotBlockCachedAccountsAndColdLookupsAreCoalesced() throws Exception {
        TestServer.initialize();
        var store=(UserDataAccessor<CoinsUser>)mock(UserDataAccessor.class);
        var data=mock(DataHandler.class); when(data.getUserDataAccessor()).thenReturn(store);
        var users=new UserManager(mock(EconomyPlugin.class),new CurrencyRegistry(),data);
        var money=TestServer.currency("money"); var cached=TestServer.user("hot",money,100);
        var cold=TestServer.user("cold",money,50); users.cachePermanent(cached);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(store.loadById(cold.getId())).thenAnswer(call->{ entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return Optional.of(cold); });
        try(var pool=Executors.newFixedThreadPool(8)) {
            var results=new ArrayList<Future<Optional<CoinsUser>>>();
            for(int i=0;i<8;i++) results.add(pool.submit(()->users.findById(cold.getId())));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { assertTimeoutPreemptively(java.time.Duration.ofMillis(300),()->assertSame(cached,users.findById(cached.getId()).orElseThrow())); }
            finally { release.countDown(); }
            for(var future:results) assertSame(cold,future.get().orElseThrow());
            verify(store,times(1)).loadById(cold.getId());
        }
    }
    @Test @SuppressWarnings("unchecked")
    void dirtyAccountsSurviveExpiredCacheAndUnavailableDatabase() {
        TestServer.initialize();
        var store=(UserDataAccessor<CoinsUser>)mock(UserDataAccessor.class);
        var data=mock(DataHandler.class); when(data.getUserDataAccessor()).thenReturn(store);
        var users=new UserManager(mock(EconomyPlugin.class),new CurrencyRegistry(),data);
        var money=TestServer.currency("money"); var user=TestServer.user("Owner",money,100);
        users.cachePermanent(user); user.addBalance(money,5);
        var expired=new su.nightexpress.nightcore.user.cache.CachedUser<>(user,System.currentTimeMillis()-1000);
        users.getRepository().getLoadedByIdMap().put(user.getId(),expired);
        users.getRepository().getLoadedByNameMap().put("owner",expired);
        doThrow(new IllegalStateException("offline")).when(store).update(anyCollection());
        users.saveDirty(); users.cleanCache();
        assertSame(user,users.findById(user.getId()).orElseThrow());
        assertSame(user,users.findByName("OWNER").orElseThrow());
        assertEquals(105,user.getBalance(money)); assertTrue(user.isDirty());
        verify(store,never()).loadById(any()); verify(store,never()).loadByName(any());
    }

    @Test @SuppressWarnings("unchecked")
    void anInflightLookupCannotInstallAPreMaintenanceSnapshot() throws Exception {
        TestServer.initialize();
        var store=(UserDataAccessor<CoinsUser>)mock(UserDataAccessor.class);
        var data=mock(DataHandler.class); when(data.getUserDataAccessor()).thenReturn(store);
        var users=new UserManager(mock(EconomyPlugin.class),new CurrencyRegistry(),data);
        var money=TestServer.currency("money"); var old=TestServer.user("user",money,100);
        var fresh=old.snapshot(); fresh.getBalance().set("money",0);
        CountDownLatch fetched=new CountDownLatch(1), release=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(store.loadById(old.getId())).thenAnswer(call -> { if(calls.getAndIncrement()==0) { fetched.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return Optional.of(old); } return Optional.of(fresh); });
        try(var executor=Executors.newSingleThreadExecutor()) {
            var lookup=executor.submit(()->users.findById(old.getId())); assertTrue(fetched.await(5,TimeUnit.SECONDS));
            try { users.maintenance(()->{}); } finally { release.countDown(); }
            assertSame(fresh,lookup.get().orElseThrow()); assertEquals(0,users.findById(old.getId()).orElseThrow().getBalance(money));
        }
        verify(store,times(2)).loadById(old.getId());
    }

    @Test @SuppressWarnings("unchecked")
    void asyncNamesPreferTheOnlineUuidAndPlayerOperationsNeverLoadSql() throws Exception {
        TestServer.initialize();
        var store=(UserDataAccessor<CoinsUser>)mock(UserDataAccessor.class);
        var data=mock(DataHandler.class); when(data.getUserDataAccessor()).thenReturn(store);
        var users=new UserManager(mock(EconomyPlugin.class),new CurrencyRegistry(),data);
        var money=TestServer.currency("money"); var old=TestServer.user("ReusedName",money,100);
        var current=TestServer.user("ReusedName",money,50);
        users.cachePermanent(old);
        org.bukkit.entity.Player player=mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(current.getId());
        when(org.bukkit.Bukkit.getPlayerExact("ReusedName")).thenReturn(player);
        when(store.loadById(current.getId())).thenReturn(Optional.of(current));
        try {
            assertThrows(IllegalStateException.class,()->users.requireLoaded(player));
            verifyNoInteractions(store);
            assertSame(current,users.findByNameAsync("ReusedName").get().orElseThrow());
            assertSame(current,users.requireLoaded(player));
        } finally { when(org.bukkit.Bukkit.getPlayerExact("ReusedName")).thenReturn(null); }
    }

}
