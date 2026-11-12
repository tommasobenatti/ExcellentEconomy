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
}
