package su.nightexpress.excellenteconomy.currency;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext;
import su.nightexpress.excellenteconomy.command.CommandManager;
import su.nightexpress.excellenteconomy.config.Perms;
import su.nightexpress.excellenteconomy.currency.tax.TransferTax;
import su.nightexpress.excellenteconomy.data.DataHandler;
import su.nightexpress.excellenteconomy.user.CoinsUser;
import su.nightexpress.excellenteconomy.user.UserManager;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrencyManagerTest {

    private ExcellentCurrency currency;
    private CoinsUser senderUser;
    private CoinsUser receiver;
    private Player sender;
    private CurrencyManager manager;

    @BeforeEach
    void setup() {
        currency = TestServer.currency("money");
        senderUser = TestServer.user("sender", currency, 1_000);
        receiver = TestServer.user("receiver", currency, 0);
        sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(senderUser.getId());
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.hasPermission(Perms.taxBypass(currency))).thenReturn(false);
        UserManager users = mock(UserManager.class);
        when(users.requireLoaded(sender)).thenReturn(senderUser);
        manager = new CurrencyManager(mock(EconomyPlugin.class), new CurrencyRegistry(), mock(CommandManager.class), mock(DataHandler.class), users);
    }

    @Test
    void senderPaysTheTaxAndReceiverGetsTheRequestedAmount() {
        when(currency.getTransferTax()).thenReturn(new TransferTax(true, TransferTax.Payer.SENDER,
            List.of(new TransferTax.Bracket(0, 10)), List.of()));
        assertTrue(manager.send(sender, receiver, currency, 100));
        assertEquals(890, senderUser.getBalance(currency));
        assertEquals(100, receiver.getBalance(currency));
    }

    @Test
    void racingPayCallsKeepTheCombinedBalanceConstant() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 20; round++) {
                senderUser.getBalance().set("money", 1_000);
                receiver.getBalance().set("money", 0);
                CyclicBarrier start = new CyclicBarrier(8);
                List<Callable<Boolean>> calls = new ArrayList<>();
                for (int i = 0; i < 8; i++) calls.add(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return manager.send(sender, receiver, currency, 900);
                });
                int successes = 0;
                for (Future<Boolean> result : pool.invokeAll(calls)) if (result.get()) successes++;
                assertEquals(1, successes);
                assertEquals(1_000, senderUser.getBalance(currency) + receiver.getBalance(currency));
            }
        }
    }

    @Test
    void receiverPaysTheTaxWhenConfigured() {
        when(currency.getTransferTax()).thenReturn(new TransferTax(true, TransferTax.Payer.RECEIVER,
            List.of(new TransferTax.Bracket(0, 10)), List.of()));
        assertTrue(manager.send(sender, receiver, currency, 100));
        assertEquals(900, senderUser.getBalance(currency));
        assertEquals(90, receiver.getBalance(currency));
    }

    @Test
    void insufficientTaxFundsAndRevokedPermissionCannotChangeBalances() {
        when(currency.getTransferTax()).thenReturn(new TransferTax(true, TransferTax.Payer.SENDER,
            List.of(new TransferTax.Bracket(0, 10)), List.of()));
        assertFalse(manager.send(sender, receiver, currency, 1_000));
        when(sender.hasPermission("excellenteconomy.command.money.send")).thenReturn(false);
        assertFalse(manager.send(sender, receiver, currency, 100));
        assertEquals(1_000, senderUser.getBalance(currency));
        assertEquals(0, receiver.getBalance(currency));
    }

    @Test
    void maintenanceCanOnlyStartOnceAndBlocksApiOperations() {
        assertTrue(manager.beginMaintenance());
        assertFalse(manager.beginMaintenance());
        assertTrue(manager.give(OperationContext.custom("test").silent(), senderUser, currency, 10).failure());
        manager.allowOperations();
        assertTrue(manager.give(OperationContext.custom("test").silent(), senderUser, currency, 10).success());
    }

    @Test
    void shuttingDownCannotBeUndoneByABackgroundMaintenanceTask() {
        manager.prepareShutdown();
        manager.allowOperations();
        assertFalse(manager.canPerformOperations());
        assertTrue(manager.give(OperationContext.custom("test").silent(), senderUser, currency, 10).failure());
        assertEquals(1_000, senderUser.getBalance(currency));
    }
}
