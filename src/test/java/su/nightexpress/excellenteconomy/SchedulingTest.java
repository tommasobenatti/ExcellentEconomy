package su.nightexpress.excellenteconomy;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.user.UserManager;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;

import static org.mockito.Mockito.*;

class SchedulingTest {

    @Test
    void asyncLookupWaitsForTheSendersScheduledCallback() {
        TestServer.initialize();
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        Player player = mock(Player.class);
        var users = spy(new UserManager(plugin, new CurrencyRegistry(), null));
        var future = new CompletableFuture<Optional<su.nightexpress.excellenteconomy.user.CoinsUser>>();
        doReturn(future).when(users).findByNameAsync("target");
        var callbacks = new ArrayList<Runnable>();
        doAnswer(call -> { callbacks.add(call.getArgument(1)); return null; })
            .when(plugin).runForSender(eq(player), any(Runnable.class));
        Runnable action = mock(Runnable.class);
        users.withUser(player, "target", user -> action.run());
        future.complete(Optional.empty());
        verifyNoInteractions(action);
        org.junit.jupiter.api.Assertions.assertEquals(1, callbacks.size());
        callbacks.getFirst().run();
        verify(action).run();
    }

    @Test
    void playerCallbacksUseTheEntitySchedulerAndConsoleUsesTheGlobalScheduler() {
        TestServer.initialize();
        EconomyPlugin plugin = mock(EconomyPlugin.class, CALLS_REAL_METHODS);
        Player player = mock(Player.class);
        CommandSender console = mock(CommandSender.class);
        Runnable work = mock(Runnable.class);
        doNothing().when(plugin).runTask(player, work);
        doNothing().when(plugin).runTask(work);
        plugin.runForSender(player, work);
        plugin.runForSender(console, work);
        verify(plugin).runTask(player, work);
        verify(plugin).runTask(work);
        verifyNoInteractions(work);
    }
}
