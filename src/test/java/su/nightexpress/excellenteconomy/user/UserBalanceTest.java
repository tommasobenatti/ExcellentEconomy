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

    @Test
    void legacyNegativeRowsCannotLeakIntoRawSnapshots() {
        assertEquals(0D, new UserBalance(Map.of("money", -10D)).getBalanceMap().get("money"));
    }

    @Test
    void rejectsNonfiniteAndOverflowingValuesWithoutChangingTheBalance() {
        UserBalance balance = new UserBalance();
        assertThrows(IllegalArgumentException.class, () -> balance.set("money", Double.NaN));
        balance.set("money", Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> balance.add("money", Double.MAX_VALUE));
        assertEquals(Double.MAX_VALUE, balance.get("money"));
    }
}
