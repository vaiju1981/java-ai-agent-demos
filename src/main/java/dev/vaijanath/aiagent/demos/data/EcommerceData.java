package dev.vaijanath.aiagent.demos.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A deterministic, multi-table e-commerce dataset (customers, products, orders, order_items) built so
 * a real analysis finds real things. Planted signal the analyst can discover and explain:
 *
 * <ul>
 *   <li><b>Seasonality</b> — Q4 revenue runs well above the rest of the year.</li>
 *   <li><b>A regional dip</b> — the West region's order volume drops sharply in Q3 (a driver of any
 *       Q3 total decline).</li>
 *   <li><b>Thin margins</b> — Electronics sells at ~15% gross margin vs. ~50% elsewhere.</li>
 *   <li><b>A return hotspot</b> — the "Cables" subcategory is returned far more often than average.</li>
 *   <li><b>Promo elasticity</b> — higher discounts correlate with higher quantity per line.</li>
 *   <li><b>Outliers</b> — a handful of bulk orders with extreme quantities.</li>
 *   <li><b>A cohort effect</b> — customers who signed up early order more.</li>
 * </ul>
 *
 * Rows stay in SQLite; the agent only ever pulls aggregates, so it scales like a real warehouse table.
 */
final class EcommerceData {

    private EcommerceData() {}

    private static final String[] REGIONS = {"North", "South", "East", "West"};
    private static final String[] SEGMENTS = {"Consumer", "Consumer", "Consumer", "SMB", "Enterprise"};
    private static final String[] CHANNELS = {"Web", "Web", "Mobile", "Mobile", "Partner"};
    private static final double[] DISCOUNTS = {0.0, 0.0, 0.0, 0.1, 0.1, 0.2, 0.3};

    // category, subcategory, target gross margin (low for Electronics → thin margins to discover).
    private static final String[][] CATALOG = {
        {"Electronics", "Laptops", "0.15"}, {"Electronics", "Phones", "0.15"},
        {"Electronics", "Audio", "0.15"}, {"Electronics", "Cables", "0.15"},
        {"Home", "Kitchen", "0.50"}, {"Home", "Furniture", "0.50"}, {"Home", "Decor", "0.50"},
        {"Apparel", "Mens", "0.55"}, {"Apparel", "Womens", "0.55"}, {"Apparel", "Kids", "0.55"},
        {"Sports", "Outdoor", "0.45"}, {"Sports", "Fitness", "0.45"},
    };

    private record Customer(int id, String region, String segment, boolean earlyCohort) {}

    private record Product(int id, String category, String subcategory, double listPrice, double unitCost) {}

    static String createDb(int customers, int orders) throws Exception {
        Path file = Files.createTempFile("jaa-ecommerce-", ".sqlite");
        file.toFile().deleteOnExit();
        String url = "jdbc:sqlite:" + file;
        Random rnd = new Random(42);

        try (Connection c = DriverManager.getConnection(url)) {
            schema(c);
            List<Customer> custs = insertCustomers(c, customers, rnd);
            List<Product> prods = insertProducts(c, rnd);
            insertOrders(c, orders, custs, prods, rnd);
            analyticalView(c);
        }
        return url;
    }

    /**
     * A denormalized "mart" view, one row per order line, with the revenue/cost/margin and date parts
     * an analyst works from — so the analytics tools operate on one wide table instead of re-deriving
     * joins each time. (The base tables remain queryable via the {@code sql} tool.)
     */
    private static void analyticalView(Connection c) throws Exception {
        c.setAutoCommit(true); // the batch inserts left autoCommit off; this DDL must persist
        try (Statement s = c.createStatement()) {
            s.execute("CREATE VIEW order_lines AS SELECT "
                    + "o.order_id, o.order_date, substr(o.order_date,1,7) AS order_month, "
                    + "o.channel, o.status, o.discount_pct, "
                    + "c.customer_id, c.region, c.segment, c.signup_date, "
                    + "p.product_id, p.category, p.subcategory, p.list_price, p.unit_cost, "
                    + "oi.quantity, oi.unit_price, "
                    + "ROUND(oi.quantity * oi.unit_price * (1 - o.discount_pct), 2) AS revenue, "
                    + "ROUND(oi.quantity * p.unit_cost, 2) AS cost, "
                    + "ROUND(oi.quantity * (oi.unit_price * (1 - o.discount_pct) - p.unit_cost), 2) AS margin "
                    + "FROM order_items oi "
                    + "JOIN orders o ON o.order_id = oi.order_id "
                    + "JOIN products p ON p.product_id = oi.product_id "
                    + "JOIN customers c ON c.customer_id = o.customer_id");
        }
    }

    private static void schema(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE customers (customer_id INTEGER PRIMARY KEY, signup_date TEXT, "
                    + "segment TEXT, region TEXT, country TEXT)");
            s.execute("CREATE TABLE products (product_id INTEGER PRIMARY KEY, name TEXT, category TEXT, "
                    + "subcategory TEXT, unit_cost REAL, list_price REAL)");
            s.execute("CREATE TABLE orders (order_id INTEGER PRIMARY KEY, customer_id INTEGER, "
                    + "order_date TEXT, channel TEXT, status TEXT, discount_pct REAL)");
            s.execute("CREATE TABLE order_items (item_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "order_id INTEGER, product_id INTEGER, quantity INTEGER, unit_price REAL)");
        }
    }

    private static List<Customer> insertCustomers(Connection c, int n, Random rnd) throws Exception {
        List<Customer> out = new ArrayList<>();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO customers VALUES (?,?,?,?,?)")) {
            for (int id = 1; id <= n; id++) {
                // Signups across 2024; an early cohort (Jan–Mar) we make more active later.
                LocalDate signup = LocalDate.of(2024, 1, 1).plusDays(rnd.nextInt(365));
                boolean early = signup.getMonthValue() <= 3;
                String region = REGIONS[rnd.nextInt(REGIONS.length)];
                String segment = SEGMENTS[rnd.nextInt(SEGMENTS.length)];
                ps.setInt(1, id);
                ps.setString(2, signup.toString());
                ps.setString(3, segment);
                ps.setString(4, region);
                ps.setString(5, rnd.nextInt(10) == 0 ? "CA" : "US");
                ps.addBatch();
                out.add(new Customer(id, region, segment, early));
                if (id % 1000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        c.commit();
        return out;
    }

    private static List<Product> insertProducts(Connection c, Random rnd) throws Exception {
        List<Product> out = new ArrayList<>();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO products VALUES (?,?,?,?,?,?)")) {
            int id = 1;
            for (String[] cat : CATALOG) {
                int perSubcat = 8 + rnd.nextInt(6);
                for (int k = 0; k < perSubcat; k++) {
                    double listPrice = 10 + rnd.nextInt(990); // $10–$1000
                    double margin = Double.parseDouble(cat[2]);
                    double unitCost = Stats.round(listPrice * (1 - margin), 2);
                    ps.setInt(1, id);
                    ps.setString(2, cat[1] + "-" + k);
                    ps.setString(3, cat[0]);
                    ps.setString(4, cat[1]);
                    ps.setDouble(5, unitCost);
                    ps.setDouble(6, listPrice);
                    ps.addBatch();
                    out.add(new Product(id, cat[0], cat[1], listPrice, unitCost));
                    id++;
                }
            }
            ps.executeBatch();
        }
        c.commit();
        return out;
    }

    private static void insertOrders(
            Connection c, int target, List<Customer> custs, List<Product> prods, Random rnd)
            throws Exception {
        c.setAutoCommit(false);
        int orderId = 1;
        int outliersLeft = 25;
        try (PreparedStatement po = c.prepareStatement("INSERT INTO orders VALUES (?,?,?,?,?,?)");
                PreparedStatement pi = c.prepareStatement(
                        "INSERT INTO order_items(order_id, product_id, quantity, unit_price) VALUES (?,?,?,?)")) {
            int made = 0;
            while (made < target) {
                Customer cust = pickCustomer(custs, rnd);
                LocalDate date = pickDate(rnd);
                // Planted dip: thin out West-region orders in Q3.
                if (cust.region().equals("West") && date.getMonthValue() >= 7 && date.getMonthValue() <= 9
                        && rnd.nextDouble() < 0.4) {
                    continue;
                }
                double discount = DISCOUNTS[rnd.nextInt(DISCOUNTS.length)];
                int lines = 1 + rnd.nextInt(4);
                boolean hasCables = false;
                List<int[]> items = new ArrayList<>(); // {productIndex, quantity}
                for (int l = 0; l < lines; l++) {
                    Product p = prods.get(rnd.nextInt(prods.size()));
                    if (p.subcategory().equals("Cables")) {
                        hasCables = true;
                    }
                    int qty = 1 + rnd.nextInt(4) + (int) Math.round(discount * 10 * rnd.nextDouble());
                    if (outliersLeft > 0 && rnd.nextDouble() < 0.002) {
                        qty = 50 + rnd.nextInt(50); // a bulk-order outlier
                        outliersLeft--;
                    }
                    items.add(new int[] {p.id(), qty});
                }
                String status = pickStatus(hasCables, rnd);
                po.setInt(1, orderId);
                po.setInt(2, cust.id());
                po.setString(3, date.toString());
                po.setString(4, CHANNELS[rnd.nextInt(CHANNELS.length)]);
                po.setString(5, status);
                po.setDouble(6, discount);
                po.addBatch();
                for (int[] it : items) {
                    pi.setInt(1, orderId);
                    pi.setInt(2, it[0]);
                    pi.setInt(3, it[1]);
                    pi.setDouble(4, prods.get(it[0] - 1).listPrice());
                    pi.addBatch();
                }
                orderId++;
                made++;
                if (made % 1000 == 0) {
                    po.executeBatch();
                    pi.executeBatch();
                }
            }
            po.executeBatch();
            pi.executeBatch();
        }
        c.commit();
    }

    private static Customer pickCustomer(List<Customer> custs, Random rnd) {
        // Early-cohort customers are twice as likely to be picked (a discoverable cohort effect).
        while (true) {
            Customer cust = custs.get(rnd.nextInt(custs.size()));
            if (cust.earlyCohort() || rnd.nextBoolean()) {
                return cust;
            }
        }
    }

    private static LocalDate pickDate(Random rnd) {
        // Month weights give a Q4 seasonal peak.
        int[] weights = {8, 8, 9, 9, 10, 10, 9, 9, 10, 13, 15, 16};
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int pick = rnd.nextInt(total);
        int month = 0;
        while (pick >= weights[month]) {
            pick -= weights[month];
            month++;
        }
        return LocalDate.of(2025, month + 1, 1 + rnd.nextInt(28));
    }

    private static String pickStatus(boolean hasCables, Random rnd) {
        double r = rnd.nextDouble();
        if (hasCables) {
            return r < 0.25 ? "returned" : (r < 0.28 ? "cancelled" : "completed");
        }
        return r < 0.05 ? "returned" : (r < 0.08 ? "cancelled" : "completed");
    }
}
