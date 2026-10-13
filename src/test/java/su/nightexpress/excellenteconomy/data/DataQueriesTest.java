package su.nightexpress.excellenteconomy.data;

import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.TestServer;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataQueriesTest {

    @Test
    void loadingLegacyNegativeRowsDoesNotTurnDebtIntoMoney() throws Exception {
        var currency = TestServer.currency("stored_money");
        when(currency.getColumnName()).thenReturn("stored_money");
        var column = DataColumns.forCurrency(currency);
        ResultSet row = mock(ResultSet.class);
        when(row.getDouble(column.getName())).thenReturn(-100D);
        try {
            var balance = DataQueries.USER_BALANCE.map(row);
            assertEquals(0, balance.get("stored_money"));
            assertEquals(0, balance.getBalanceMap().get("stored_money"));
        }
        finally { DataColumns.uncacheCurrency(currency); }
    }
}
