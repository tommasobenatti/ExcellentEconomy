package su.nightexpress.excellenteconomy.currency;

import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.currency.tax.TransferTax;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransferTaxTest {

    private final List<TransferTax.Bracket> brackets = List.of(
        new TransferTax.Bracket(0, 0), new TransferTax.Bracket(100, 5), new TransferTax.Bracket(1_000, 10));

    @Test
    void chargesEachProgressiveBracketOnlyAboveItsThreshold() {
        TransferTax tax = new TransferTax(true, TransferTax.Payer.SENDER, brackets, List.of());
        assertEquals(0, tax.quote(100, true, permission -> false).tax());
        assertEquals(5, tax.quote(200, true, permission -> false).tax());
        TransferTax.Quote quote = tax.quote(2_000, true, permission -> false);
        assertEquals(145, quote.tax());
        assertEquals(2_145, quote.debit());
        assertEquals(2_000, quote.credit());
    }

    @Test
    void receiverPaysWithoutChangingTheSenderDebit() {
        TransferTax tax = new TransferTax(true, TransferTax.Payer.RECEIVER, brackets, List.of());
        TransferTax.Quote quote = tax.quote(2_000, true, permission -> false);
        assertEquals(2_000, quote.debit());
        assertEquals(1_855, quote.credit());
    }

    @Test
    void mostSpecificPermissionTierWinsByPriority() {
        TransferTax tax = new TransferTax(true, TransferTax.Payer.SENDER, brackets, List.of(
            new TransferTax.Tier("vip", "tax.vip", 10, List.of(new TransferTax.Bracket(0, 2))),
            new TransferTax.Tier("staff", "tax.staff", 20, List.of(new TransferTax.Bracket(0, 0)))));
        assertEquals(2, tax.quote(100, true, "tax.vip"::equals).tax());
        assertEquals(0, tax.quote(100, true, permission -> true).tax());
    }

    @Test
    void disabledTaxesAndIntegerRoundingPreserveAmounts() {
        TransferTax disabled = new TransferTax(false, TransferTax.Payer.SENDER, brackets, List.of());
        assertEquals(0, disabled.quote(200, true, permission -> false).tax());
        TransferTax tax = new TransferTax(true, TransferTax.Payer.SENDER, List.of(new TransferTax.Bracket(0, 1.5)), List.of());
        assertEquals(1, tax.quote(100, false, permission -> false).tax());
        assertEquals(1.5, tax.quote(100, true, permission -> false).tax());
    }

    @Test
    void rejectsInvalidRatesDuplicateThresholdsAndAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new TransferTax.Bracket(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TransferTax.Bracket(0, 101));
        assertThrows(IllegalArgumentException.class, () -> new TransferTax.Bracket(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TransferTax(true, TransferTax.Payer.SENDER,
            List.of(new TransferTax.Bracket(0, 1), new TransferTax.Bracket(0, 2)), List.of()));
        TransferTax tax = new TransferTax(true, TransferTax.Payer.SENDER, brackets, List.of());
        assertThrows(IllegalArgumentException.class, () -> tax.quote(Double.POSITIVE_INFINITY, true, permission -> false));
    }
}
