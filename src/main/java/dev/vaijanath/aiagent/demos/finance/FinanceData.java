package dev.vaijanath.aiagent.demos.finance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.Random;

/**
 * A deterministic year of one person's finances (income + categorized expenses + subscriptions),
 * built so a real advisory review finds real things:
 *
 * <ul>
 *   <li><b>Lifestyle creep</b> — Dining spend climbs steadily through the year.</li>
 *   <li><b>A new subscription</b> — a gym membership starts mid-year (July).</li>
 *   <li><b>An anomalous expense</b> — a one-off ~$3,500 trip in August.</li>
 *   <li><b>A declining savings rate</b> — net savings shrink in H2 as expenses rise.</li>
 *   <li><b>An over-budget category</b> — Dining runs past its monthly budget late in the year.</li>
 * </ul>
 *
 * One {@code transactions} table with a {@code type} of income or expense; rows stay in SQLite.
 */
final class FinanceData {

    private FinanceData() {}

    /** Monthly category budgets the advisory tools compare against. */
    static final java.util.Map<String, Double> BUDGETS = java.util.Map.of(
            "Dining", 450.0, "Groceries", 700.0, "Transport", 200.0, "Utilities", 250.0,
            "Entertainment", 150.0, "Shopping", 400.0, "Health", 150.0, "Travel", 300.0);

    static String createDb() throws Exception {
        Path file = Files.createTempFile("jaa-finance-", ".sqlite");
        file.toFile().deleteOnExit();
        String url = "jdbc:sqlite:" + file;
        Random rnd = new Random(99);

        try (Connection c = DriverManager.getConnection(url)) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "txn_date TEXT, type TEXT, category TEXT, merchant TEXT, amount REAL)");
            }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(txn_date, type, category, merchant, amount) VALUES (?,?,?,?,?)")) {
                for (int month = 1; month <= 12; month++) {
                    YearMonth ym = YearMonth.of(2025, month);
                    income(ps, ym, month);
                    expenses(ps, ym, month, rnd);
                    subscriptions(ps, ym, month);
                }
            }
            c.commit();
        }
        return url;
    }

    private static void income(PreparedStatement ps, YearMonth ym, int month) throws Exception {
        // Steady salary, no bonus — so the second-half savings decline is driven purely by rising
        // expenses (dining creep + new gym + the August trip), which is the signal under review.
        row(ps, ym.atDay(1), "income", "Salary", "Employer", 6500.0);
    }

    private static void expenses(PreparedStatement ps, YearMonth ym, int month, Random rnd) throws Exception {
        row(ps, ym.atDay(2), "expense", "Housing", "Landlord", 2200.0); // fixed rent

        // Dining creeps up through the year (lifestyle creep), spread over several outings.
        double diningTarget = 300 + month * 28;
        spread(ps, ym, "expense", "Dining", "Restaurant", diningTarget, 6 + rnd.nextInt(5), rnd);

        spread(ps, ym, "expense", "Groceries", "Market", 520 + rnd.nextInt(180), 4 + rnd.nextInt(4), rnd);
        spread(ps, ym, "expense", "Transport", "Transit", 120 + rnd.nextInt(80), 3 + rnd.nextInt(3), rnd);
        row(ps, ym.atDay(8), "expense", "Utilities", "Utility Co", 180 + rnd.nextInt(70));
        spread(ps, ym, "expense", "Entertainment", "Various", 80 + rnd.nextInt(70), 2 + rnd.nextInt(3), rnd);
        spread(ps, ym, "expense", "Shopping", "Retailer", 150 + rnd.nextInt(300), 2 + rnd.nextInt(4), rnd);
        row(ps, ym.atDay(15), "expense", "Health", "Pharmacy", 60 + rnd.nextInt(60));

        // A one-off anomalous trip in August; otherwise modest occasional travel.
        if (month == 8) {
            row(ps, ym.atDay(12), "expense", "Travel", "Airline", 3500.0);
        } else if (rnd.nextBoolean()) {
            row(ps, ym.atDay(18), "expense", "Travel", "Airline", 80 + rnd.nextInt(220));
        }
    }

    private static void subscriptions(PreparedStatement ps, YearMonth ym, int month) throws Exception {
        row(ps, ym.atDay(5), "expense", "Subscriptions", "Netflix", 15.49);
        row(ps, ym.atDay(5), "expense", "Subscriptions", "Spotify", 9.99);
        row(ps, ym.atDay(6), "expense", "Subscriptions", "CloudDrive", 9.99);
        if (month >= 7) {
            row(ps, ym.atDay(7), "expense", "Subscriptions", "FitGym", 49.99); // started in July
        }
    }

    /** Spreads a monthly target across {@code n} transactions on varied days. */
    private static void spread(PreparedStatement ps, YearMonth ym, String type, String category,
            String merchant, double target, int n, Random rnd) throws Exception {
        double remaining = target;
        for (int i = 0; i < n; i++) {
            double amount = i == n - 1 ? remaining : Math.round((target / n) * (0.6 + rnd.nextDouble() * 0.8) * 100) / 100.0;
            amount = Math.min(amount, remaining);
            remaining -= amount;
            int day = 1 + rnd.nextInt(ym.lengthOfMonth());
            row(ps, ym.atDay(day), type, category, merchant, Math.max(0.01, Math.round(amount * 100) / 100.0));
        }
    }

    private static void row(PreparedStatement ps, java.time.LocalDate date, String type, String category,
            String merchant, double amount) throws Exception {
        ps.setString(1, date.toString());
        ps.setString(2, type);
        ps.setString(3, category);
        ps.setString(4, merchant);
        ps.setDouble(5, Math.round(amount * 100) / 100.0);
        ps.executeUpdate();
    }
}
