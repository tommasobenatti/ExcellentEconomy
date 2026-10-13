package su.nightexpress.excellenteconomy.currency.tax;

import su.nightexpress.nightcore.config.FileConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public record TransferTax(boolean enabled, Payer payer, List<Bracket> brackets, List<Tier> tiers) {

    public static final TransferTax NONE = new TransferTax(false, Payer.SENDER, List.of(), List.of());

    public enum Payer { SENDER, RECEIVER }

    public record Bracket(double threshold, double percent) {
        public Bracket {
            if (!Double.isFinite(threshold) || threshold < 0 || !Double.isFinite(percent) || percent < 0 || percent > 100)
                throw new IllegalArgumentException("Tax thresholds must be nonnegative and rates between 0 and 100");
        }
    }

    public record Tier(String id, String permission, int priority, List<Bracket> brackets) {
        public Tier {
            if (permission.isBlank()) throw new IllegalArgumentException("A tax tier must have a permission");
            brackets = sortedBrackets(brackets);
        }
    }

    public record Quote(double tax, double debit, double credit) {}

    public TransferTax {
        if (payer == null) throw new IllegalArgumentException("Tax payer must be SENDER or RECEIVER");
        brackets = sortedBrackets(brackets);
        tiers = tiers.stream().sorted(Comparator.comparingInt(Tier::priority).reversed().thenComparing(Tier::id)).toList();
    }

    private static List<Bracket> sortedBrackets(List<Bracket> brackets) {
        List<Bracket> sorted = brackets.stream().sorted(Comparator.comparingDouble(Bracket::threshold)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).threshold == sorted.get(i).threshold)
                throw new IllegalArgumentException("Tax thresholds must be unique");
        }
        return sorted;
    }

    public Quote quote(double amount, boolean decimal, Predicate<String> hasPermission) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Transfer amount must be finite and nonnegative");
        List<Bracket> selected = this.tiers.stream().filter(tier -> hasPermission.test(tier.permission))
            .findFirst().map(Tier::brackets).orElse(this.brackets);
        BigDecimal tax = BigDecimal.ZERO;
        if (this.enabled) {
            for (int i = 0; i < selected.size(); i++) {
                Bracket bracket = selected.get(i);
                double upper = i + 1 < selected.size() ? Math.min(amount, selected.get(i + 1).threshold) : amount;
                if (upper <= bracket.threshold) break;
                BigDecimal portion = BigDecimal.valueOf(upper).subtract(BigDecimal.valueOf(bracket.threshold));
                tax = tax.add(portion.multiply(BigDecimal.valueOf(bracket.percent)).movePointLeft(2));
            }
        }
        // Match the existing currency precision; whole currencies never charge fractional units.
        double fee = tax.setScale(decimal ? 8 : 0, RoundingMode.DOWN).doubleValue();
        BigDecimal value = BigDecimal.valueOf(amount);
        BigDecimal rounded = BigDecimal.valueOf(fee);
        double debit = this.payer == Payer.SENDER ? value.add(rounded).doubleValue() : amount;
        double credit = this.payer == Payer.RECEIVER ? value.subtract(rounded).doubleValue() : amount;
        if (!Double.isFinite(debit)) throw new IllegalArgumentException("Transfer total is too large");
        return new Quote(fee, debit, credit);
    }

    public static TransferTax read(FileConfig config) {
        boolean enabled = config.getBoolean("Transfer_Tax.Enabled", false);
        Payer payer = Payer.valueOf(config.getString("Transfer_Tax.Payer", "SENDER").toUpperCase(Locale.ROOT));
        if (!config.contains("Transfer_Tax")) {
            config.set("Transfer_Tax.Enabled", false);
            config.set("Transfer_Tax.Payer", "SENDER");
            config.set("Transfer_Tax.Brackets.base.Threshold", 0D);
            config.set("Transfer_Tax.Brackets.base.Percent", 0D);
        }
        List<Tier> tiers = new ArrayList<>();
        for (String id : config.getSection("Transfer_Tax.Permission_Tiers")) {
            String path = "Transfer_Tax.Permission_Tiers." + id;
            tiers.add(new Tier(id, config.getString(path + ".Permission", ""), config.getInt(path + ".Priority", 0),
                readBrackets(config, path + ".Brackets")));
        }
        return new TransferTax(enabled, payer, readBrackets(config, "Transfer_Tax.Brackets"), tiers);
    }

    private static List<Bracket> readBrackets(FileConfig config, String path) {
        List<Bracket> brackets = new ArrayList<>();
        for (String id : config.getSection(path)) {
            brackets.add(new Bracket(config.getDouble(path + "." + id + ".Threshold", Double.NaN),
                config.getDouble(path + "." + id + ".Percent", Double.NaN)));
        }
        return brackets;
    }
}
