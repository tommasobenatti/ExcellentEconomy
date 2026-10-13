package su.nightexpress.excellenteconomy.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BalanceTransactionsTest {

    private final ExcellentCurrency currency = TestServer.currency("money");

    @AfterEach
    void resetEvents() {
        reset(TestServer.PLUGINS);
    }

    @Test
    void racingPaymentsCannotMintMoney() throws Exception {
        CoinsUser sender = TestServer.user("sender", currency, 20_000);
        CoinsUser receiver = TestServer.user("receiver", currency, 0);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return BalanceTransactions.transfer(sender, currency, receiver, currency, 19_999, 19_999);
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(20_000, sender.getBalance(currency) + receiver.getBalance(currency));
        }
    }

    @Test
    void cancelledCreditRollsBackBothSides() {
        CoinsUser sender = TestServer.user("sender", currency, 100);
        CoinsUser receiver = TestServer.user("receiver", currency, 0);
        doAnswer(call -> {
            ChangeBalanceEvent event = call.getArgument(0);
            if (event.getUser() == receiver) event.setCancelled(true);
            return null;
        }).when(TestServer.PLUGINS).callEvent(any(ChangeBalanceEvent.class));

        assertFalse(BalanceTransactions.transfer(sender, currency, receiver, currency, 50, 50));
        assertEquals(100, sender.getBalance(currency));
        assertEquals(0, receiver.getBalance(currency));
    }

    @Test
    void cancelledTransferCannotRefundASuccessfulNestedWithdrawal() {
        CoinsUser sender = TestServer.user("sender", currency, 100);
        CoinsUser receiver = TestServer.user("receiver", currency, 0);
        doAnswer(call -> {
            ChangeBalanceEvent event = call.getArgument(0);
            if (event.getUser() == receiver) {
                assertFalse(BalanceTransactions.withdraw(sender, currency, 1));
                event.setCancelled(true);
            }
            return null;
        }).when(TestServer.PLUGINS).callEvent(any(ChangeBalanceEvent.class));
        assertFalse(BalanceTransactions.transfer(sender, currency, receiver, currency, 50, 50));
        assertEquals(100, sender.getBalance(currency));
        assertEquals(0, receiver.getBalance(currency));
    }

    @Test
    void receiverLimitAndInsufficientFundsLeaveBalancesUntouched() {
        CoinsUser sender = TestServer.user("sender", currency, 100);
        CoinsUser receiver = TestServer.user("receiver", currency, 80);
        when(currency.isUnderLimit(130)).thenReturn(false);
        assertFalse(BalanceTransactions.transfer(sender, currency, receiver, currency, 50, 50));
        assertFalse(BalanceTransactions.withdraw(sender, currency, 101));
        assertEquals(100, sender.getBalance(currency));
        assertEquals(80, receiver.getBalance(currency));
    }

    @Test
    void rejectsInvalidAmountsInsteadOfTurningThemIntoPositiveMoney() {
        CoinsUser user = TestServer.user("user", currency, 100);
        for (double amount : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(BalanceTransactions.deposit(user, currency, amount));
            assertFalse(BalanceTransactions.withdraw(user, currency, amount));
        }
        assertEquals(100, user.getBalance(currency));
    }

    @Test
    void racingWithdrawalsCanOnlySpendTheExistingBalance() throws Exception {
        CoinsUser user = TestServer.user("user", currency, 100);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) calls.add(() -> BalanceTransactions.withdraw(user, currency, 10));
            int successes = 0;
            for (Future<Boolean> result : pool.invokeAll(calls)) if (result.get()) successes++;
            assertEquals(10, successes);
            assertEquals(0, user.getBalance(currency));
        }
    }

    @Test
    void exchangeCancellationDoesNotBurnTheSourceCurrency() {
        ExcellentCurrency coins = TestServer.currency("coins");
        CoinsUser user = TestServer.user("user", currency, 100);
        doAnswer(call -> {
            ChangeBalanceEvent event = call.getArgument(0);
            if (event.getCurrency() == coins) event.setCancelled(true);
            return null;
        }).when(TestServer.PLUGINS).callEvent(any(ChangeBalanceEvent.class));
        assertFalse(BalanceTransactions.transfer(user, currency, user, coins, 10, 20));
        assertEquals(100, user.getBalance(currency));
        assertEquals(0, user.getBalance(coins));
    }
}
