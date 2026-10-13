package su.nightexpress.excellenteconomy.command;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import su.nightexpress.nightcore.commands.Arguments;
import su.nightexpress.nightcore.commands.Commands;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.TestServer;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.command.currency.CommandDefinition;
import su.nightexpress.excellenteconomy.command.currency.CurrencyCommand;
import su.nightexpress.nightcore.commands.builder.LiteralNodeBuilder;
import su.nightexpress.nightcore.commands.context.CommandContext;
import su.nightexpress.nightcore.commands.context.ParsedArguments;
import java.util.ArrayList;
import java.util.List;
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
        try (MockedStatic<Arguments> arguments = mockStatic(Arguments.class)) {
            arguments.when(() -> Arguments.playerName(anyString()))
                .thenAnswer(call -> Commands.argument(call.getArgument(0), (context, string) -> string));
            arguments.when(() -> Arguments.decimalCompact(anyString(), anyDouble()))
                .thenAnswer(call -> Commands.argument(call.getArgument(0), (context, string) -> Double.parseDouble(string)));
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
    }

    @Test
    void standaloneOnlyActuallyEnablesTheStandaloneCommand() {
        assertTrue(CommandDefinition.standaloneOnly("pay", "pay").standaloneEnabled());
        assertFalse(CommandDefinition.standaloneOnly("pay", "pay").childrenEnabled());
    }

    @Test
    void disabledCommandHasNeitherSubcommandNorStandaloneAlias() {
        ExcellentCurrency money = TestServer.currency("money");
        when(money.getCommandAliases()).thenReturn(new String[]{"money"});
        when(money.getName()).thenReturn("Money");
        when(money.isPrimary()).thenReturn(true);
        when(money.isCommandEnabled("send")).thenReturn(false);
        List<NightCommand> registered = new ArrayList<>();
        CommandManager manager = new CommandManager(mock(EconomyPlugin.class), new CurrencyRegistry()) {
            @Override
            public boolean registerCurrencyCommand(ExcellentCurrency currency, NightCommand command) {
                registered.add(command);
                return true;
            }
        };
        manager.addCurrencyCommand("send", () -> new CurrencyCommand() {
            public boolean isFallback() { return false; }
            public void build(LiteralNodeBuilder builder, ExcellentCurrency currency) {}
            public boolean execute(CommandContext context, ParsedArguments arguments, ExcellentCurrency currency) { return true; }
        }, CommandDefinition.allEnabled("pay", "pay"));
        manager.registerCurrencyCommands(money);
        assertEquals(1, registered.size());
        assertNull(registered.getFirst().getRoot().getChild("pay"));
    }
}
