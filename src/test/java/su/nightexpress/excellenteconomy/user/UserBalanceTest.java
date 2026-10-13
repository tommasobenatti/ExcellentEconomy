package su.nightexpress.excellenteconomy.user;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserBalanceTest {

    @Test
    void enforcesTheExistingZeroFloorFromIssue67() {
        UserBalance balance = new UserBalance();
        balance.set("money", -10);
        assertEquals(0, balance.get("money"));
        balance.set("money", 10);
        balance.remove("money", 100);
        assertEquals(0, balance.getBalanceMap().get("money"));
    }

    @Test
    void snapshotsCannotMutateBalances() {
        UserBalance balance = new UserBalance(Map.of("money", 10D));
        assertThrows(UnsupportedOperationException.class, () -> balance.getBalanceMap().put("money", 20D));
    }
}
