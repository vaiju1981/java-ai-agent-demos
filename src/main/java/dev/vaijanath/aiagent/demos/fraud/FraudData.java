package dev.vaijanath.aiagent.demos.fraud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Random;

/**
 * A deterministic payments dataset (accounts + transactions over March 2025) with planted fraud
 * patterns an investigator can find and act on:
 *
 * <ul>
 *   <li><b>Card testing</b> — {@code ACC-CARDTEST} runs ~60 tiny charges within one hour.</li>
 *   <li><b>Velocity spike</b> — {@code ACC-VELOCITY} fires ~40 transactions in a 15-minute burst.</li>
 *   <li><b>Geo-anomaly</b> — {@code ACC-GEO} transacts from 8 countries in a single day.</li>
 *   <li><b>A transfer ring</b> — {@code ACC-RING-1/2/3} move large P2P amounts among themselves.</li>
 * </ul>
 *
 * Everything else is ordinary single-country, modest-value activity. Rows stay in SQLite.
 */
final class FraudData {

    private FraudData() {}

    private static final String[] COUNTRIES = {"US", "US", "US", "GB", "CA", "DE", "FR", "AU"};
    private static final String[] MERCHANTS = {"Amazon", "Walmart", "Uber", "Starbucks", "Apple",
        "Steam", "Shell", "Target", "Netflix", "Delta"};
    private static final String[] CHANNELS = {"card-present", "online", "online", "online"};

    static String createDb(int normalAccounts) throws Exception {
        Path file = Files.createTempFile("jaa-fraud-", ".sqlite");
        file.toFile().deleteOnExit();
        String url = "jdbc:sqlite:" + file;
        Random rnd = new Random(7);

        try (Connection c = DriverManager.getConnection(url)) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE accounts (account_id TEXT PRIMARY KEY, holder TEXT, "
                        + "home_country TEXT, created_date TEXT)");
                s.execute("CREATE TABLE transactions (txn_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "account_id TEXT, ts TEXT, amount REAL, merchant TEXT, country TEXT, "
                        + "channel TEXT, status TEXT)");
            }
            c.setAutoCommit(false);
            try (PreparedStatement pa = c.prepareStatement("INSERT INTO accounts VALUES (?,?,?,?)");
                    PreparedStatement pt = c.prepareStatement("INSERT INTO transactions"
                            + "(account_id, ts, amount, merchant, country, channel, status) VALUES (?,?,?,?,?,?,?)")) {
                for (int i = 1; i <= normalAccounts; i++) {
                    String id = String.format("ACC-%04d", i);
                    String home = COUNTRIES[rnd.nextInt(COUNTRIES.length)];
                    account(pa, id, "Holder " + i, home);
                    int txns = 5 + rnd.nextInt(26);
                    for (int t = 0; t < txns; t++) {
                        txn(pt, id, ts(rnd, 1 + rnd.nextInt(28), rnd.nextInt(24), rnd.nextInt(60)),
                                round(3 + rnd.nextDouble() * 400), MERCHANTS[rnd.nextInt(MERCHANTS.length)],
                                home, CHANNELS[rnd.nextInt(CHANNELS.length)], "approved");
                    }
                }
                plantCardTesting(pa, pt);
                plantVelocity(pa, pt);
                plantGeoAnomaly(pa, pt);
                plantRing(pa, pt);
            }
            c.commit();
        }
        return url;
    }

    private static void plantCardTesting(PreparedStatement pa, PreparedStatement pt) throws Exception {
        account(pa, "ACC-CARDTEST", "Card Tester", "US");
        for (int i = 0; i < 60; i++) {
            txn(pt, "ACC-CARDTEST", String.format("2025-03-10T03:%02d:%02d", i % 60, (i * 7) % 60),
                    round(0.5 + (i % 5)), "DigitalGoods", "US", "online", "approved");
        }
    }

    private static void plantVelocity(PreparedStatement pa, PreparedStatement pt) throws Exception {
        account(pa, "ACC-VELOCITY", "Speedy Spender", "US");
        for (int i = 0; i < 40; i++) {
            txn(pt, "ACC-VELOCITY", String.format("2025-03-15T14:%02d:%02d", i % 15, (i * 11) % 60),
                    round(50 + i * 9), MERCHANTS[i % MERCHANTS.length], "US", "online", "approved");
        }
    }

    private static void plantGeoAnomaly(PreparedStatement pa, PreparedStatement pt) throws Exception {
        account(pa, "ACC-GEO", "Globe Trotter", "US");
        String[] countries = {"US", "GB", "DE", "FR", "AU", "CA", "JP", "BR"};
        for (int i = 0; i < countries.length; i++) {
            txn(pt, "ACC-GEO", String.format("2025-03-20T%02d:00:00", i * 3),
                    round(200 + i * 120), "LuxuryGoods", countries[i], "online", "approved");
        }
    }

    private static void plantRing(PreparedStatement pa, PreparedStatement pt) throws Exception {
        String[] ring = {"ACC-RING-1", "ACC-RING-2", "ACC-RING-3"};
        for (String id : ring) {
            account(pa, id, "Ring " + id, "US");
        }
        for (int day = 5; day <= 12; day++) {
            for (int i = 0; i < ring.length; i++) {
                txn(pt, ring[i], String.format("2025-03-%02dT09:%02d:00", day, i * 5),
                        round(8000 + day * 250), "P2P-Transfer", "US", "online", "approved");
            }
        }
    }

    private static void account(PreparedStatement pa, String id, String holder, String home)
            throws Exception {
        pa.setString(1, id);
        pa.setString(2, holder);
        pa.setString(3, home);
        pa.setString(4, "2024-12-01");
        pa.executeUpdate();
    }

    private static void txn(PreparedStatement pt, String account, String ts, double amount,
            String merchant, String country, String channel, String status) throws Exception {
        pt.setString(1, account);
        pt.setString(2, ts);
        pt.setDouble(3, amount);
        pt.setString(4, merchant);
        pt.setString(5, country);
        pt.setString(6, channel);
        pt.setString(7, status);
        pt.executeUpdate();
    }

    private static String ts(Random rnd, int day, int hour, int min) {
        return String.format("2025-03-%02dT%02d:%02d:00", day, hour, min);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
