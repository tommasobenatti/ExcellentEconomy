package su.nightexpress.excellenteconomy.command;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.command.currency.CommandDefinition;
import su.nightexpress.excellenteconomy.currency.CurrencyManager;
import su.nightexpress.excellenteconomy.currency.CurrencyRegistry;
import su.nightexpress.excellenteconomy.currency.command.BalanceCommand;
import su.nightexpress.excellenteconomy.currency.command.PayCommand;
import su.nightexpress.excellenteconomy.user.UserManager;
import su.nightexpress.nightcore.commands.command.NightCommand;
import su.nightexpress.nightcore.commands.context.CommandContextBuilder;
import su.nightexpress.nightcore.commands.tree.ExecutableNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrencyCommandsTest {

    @Test
    void usesCurrencyPermissionsAndChecksTheRootBalanceFallback() throws Exception {
        EconomyPlugin plugin = mock(EconomyPlugin.class);
        CurrencyManager balances = mock(CurrencyManager.class);
        UserManager users = mock(UserManager.class);
        ExcellentCurrency money = TestServer.currency("money");
        when(money.getCommandAliases()).thenReturn(new String[]{"money"});
        when(money.getName()).thenReturn("Money");
        when(money.isPrimary()).thenReturn(true);
        CommandManager manager = new CommandManager(plugin, new CurrencyRegistry()) {
            @Override
            public boolean registerCurrencyCommand(ExcellentCurrency currency, NightCommand command) {
                if (!command.getName().equals("money")) return true;
                ExecutableNode root = (ExecutableNode) command.getRoot();
                Player player = mock(Player.class);
                assertEquals("excellenteconomy.command.money.balance", root.getChild("balance").getPermission());
                assertEquals("excellenteconomy.command.money.send", root.getChild("pay").getPermission());
                assertFalse(root.getChild("pay").hasPermission(player));
                try {
                    root.run(new CommandContextBuilder(plugin, player, root, "money").build());
                }
                catch (Exception exception) {
                    throw new AssertionError(exception);
                }
                verifyNoInteractions(balances);
                return true;
            }
        };
        manager.addCurrencyCommand("balance", () -> new BalanceCommand(balances, users), CommandDefinition.allEnabled("balance", "balance"));
        manager.addCurrencyCommand("send", () -> new PayCommand(balances, users), CommandDefinition.allEnabled("pay", "pay"));
        manager.registerCurrencyCommands(money);
    }

    @Test
    void standaloneOnlyActuallyEnablesTheStandaloneCommand() {
        assertTrue(CommandDefinition.standaloneOnly("pay", "pay").standaloneEnabled());
        assertFalse(CommandDefinition.standaloneOnly("pay", "pay").childrenEnabled());
    }
}
