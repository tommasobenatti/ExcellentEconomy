package su.nightexpress.excellenteconomy;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class SchedulingTest {

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
