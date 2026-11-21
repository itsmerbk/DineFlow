package com.dineflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RestaurantStore implements AutoCloseable {
    private final Connection conn;

    public RestaurantStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        seedInitialData();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS dining_tables (
                    table_code TEXT PRIMARY KEY,
                    zone TEXT NOT NULL,
                    capacity INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'VACANT',
                    active_order_id INTEGER
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS menu_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    price REAL NOT NULL,
                    prep_mins INTEGER NOT NULL
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_code TEXT NOT NULL,
                    status TEXT NOT NULL,
                    subtotal REAL NOT NULL DEFAULT 0.0,
                    gst REAL NOT NULL DEFAULT 0.0,
                    total REAL NOT NULL DEFAULT 0.0,
                    created_at TEXT NOT NULL,
                    closed_at TEXT
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS order_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    order_id INTEGER NOT NULL,
                    table_code TEXT NOT NULL,
                    item_name TEXT NOT NULL,
                    qty INTEGER NOT NULL,
                    unit_price REAL NOT NULL,
                    prep_mins INTEGER NOT NULL,
                    kitchen_status TEXT NOT NULL DEFAULT 'QUEUED',
                    ordered_at_ms INTEGER NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_kds_status ON order_items(kitchen_status, ordered_at_ms ASC);");
        }
    }

    private void seedInitialData() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dining_tables")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        Object[][] tables = {
            {"T1", "Main Hall", 2},
            {"T2", "Main Hall", 4},
            {"T3", "Main Hall", 4},
            {"T4", "Window Booth", 2},
            {"T5", "Window Booth", 6},
            {"T6", "Patio Deck", 4},
            {"T7", "Patio Deck", 6},
            {"T8", "VIP Lounge", 8}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO dining_tables (table_code, zone, capacity, status) VALUES (?, ?, ?, 'VACANT')")) {
            for (Object[] t : tables) {
                ps.setString(1, (String) t[0]);
                ps.setString(2, (String) t[1]);
                ps.setInt(3, (Integer) t[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        Object[][] menu = {
            {"Paneer Tikka Platter", "Starters", 280.0, 12},
            {"Crispy Chilli Baby Corn", "Starters", 240.0, 10},
            {"Butter Naan Basket (3 pcs)", "Mains", 150.0, 8},
            {"Paneer Butter Masala", "Mains", 340.0, 15},
            {"Hyderabadi Veg Dum Biryani", "Mains", 360.0, 18},
            {"Wood-Fired Margherita Pizza", "Mains", 420.0, 14},
            {"Fresh Lime Mint Cooler", "Beverages", 120.0, 4},
            {"Filter Coffee Cold Brew", "Beverages", 160.0, 5},
            {"Sizzling Chocolate Brownie", "Desserts", 220.0, 8}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO menu_items (name, category, price, prep_mins) VALUES (?, ?, ?, ?)")) {
            for (Object[] m : menu) {
                ps.setString(1, (String) m[0]);
                ps.setString(2, (String) m[1]);
                ps.setDouble(3, (Double) m[2]);
                ps.setInt(4, (Integer) m[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        addOrderItem("T2", 1, 1);
        addOrderItem("T2", 5, 2);
        advanceKitchenItem(1L);
        addOrderItem("T5", 6, 1);
        addOrderItem("T5", 7, 3);
    }

    public synchronized String addOrderItem(String tableCode, int menuItemId, int qty) throws SQLException {
        if (qty < 1) qty = 1;
        conn.setAutoCommit(false);
        try {
            String itemName;
            double unitPrice;
            int prepMins;
            try (PreparedStatement ps = conn.prepareStatement("SELECT name, price, prep_mins FROM menu_items WHERE id = ?")) {
                ps.setInt(1, menuItemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Menu item not found\"}";
                    }
                    itemName = rs.getString("name");
                    unitPrice = rs.getDouble("price");
                    prepMins = rs.getInt("prep_mins");
                }
            }

            long orderId;
            try (PreparedStatement ps = conn.prepareStatement("SELECT active_order_id FROM dining_tables WHERE table_code = ?")) {
                ps.setString(1, tableCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Table not found\"}";
                    }
                    long existingOrderId = rs.getLong("active_order_id");
                    if (rs.wasNull() || existingOrderId == 0L) {
                        try (PreparedStatement insOrder = conn.prepareStatement(
                                "INSERT INTO orders (table_code, status, created_at) VALUES (?, 'OPEN', ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                            insOrder.setString(1, tableCode);
                            insOrder.setString(2, Instant.now().toString());
                            insOrder.executeUpdate();
                            try (ResultSet keys = insOrder.getGeneratedKeys()) {
                                orderId = keys.next() ? keys.getLong(1) : 1L;
                            }
                        }
                        try (PreparedStatement updTable = conn.prepareStatement(
                                "UPDATE dining_tables SET status = 'DINING', active_order_id = ? WHERE table_code = ?")) {
                            updTable.setLong(1, orderId);
                            updTable.setString(2, tableCode);
                            updTable.executeUpdate();
                        }
                    } else {
                        orderId = existingOrderId;
                    }
                }
            }

            long kotId;
            try (PreparedStatement insItem = conn.prepareStatement(
                    """
                    INSERT INTO order_items (order_id, table_code, item_name, qty, unit_price, prep_mins, kitchen_status, ordered_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insItem.setLong(1, orderId);
                insItem.setString(2, tableCode);
                insItem.setString(3, itemName);
                insItem.setInt(4, qty);
                insItem.setDouble(5, unitPrice);
                insItem.setInt(6, prepMins);
                insItem.setLong(7, System.currentTimeMillis());
                insItem.executeUpdate();
                try (ResultSet keys = insItem.getGeneratedKeys()) {
                    kotId = keys.next() ? keys.getLong(1) : 1L;
                }
            }

            conn.commit();
            return String.format("{\"ok\":true,\"orderId\":%d,\"kotId\":%d,\"itemName\":\"%s\"}",
                orderId, kotId, JsonUtil.escape(itemName));
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String advanceKitchenItem(long kotId) throws SQLException {
        String current;
        try (PreparedStatement ps = conn.prepareStatement("SELECT kitchen_status FROM order_items WHERE id = ?")) {
            ps.setLong(1, kotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "{\"ok\":false,\"error\":\"KOT ticket not found\"}";
                }
                current = rs.getString("kitchen_status");
            }
        }

        String next = switch (current) {
            case "QUEUED" -> "PREPARING";
            case "PREPARING" -> "READY";
            default -> "SERVED";
        };

        try (PreparedStatement upd = conn.prepareStatement("UPDATE order_items SET kitchen_status = ? WHERE id = ?")) {
            upd.setString(1, next);
            upd.setLong(2, kotId);
            upd.executeUpdate();
        }
        return "{\"ok\":true,\"kotId\":" + kotId + ",\"status\":\"" + next + "\"}";
    }

    public synchronized String settleTableBill(String tableCode) throws SQLException {
        conn.setAutoCommit(false);
        try {
            long orderId;
            try (PreparedStatement ps = conn.prepareStatement("SELECT active_order_id FROM dining_tables WHERE table_code = ?")) {
                ps.setString(1, tableCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Table not found\"}";
                    }
                    orderId = rs.getLong("active_order_id");
                    if (rs.wasNull() || orderId == 0L) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"No active order on table " + JsonUtil.escape(tableCode) + "\"}";
                    }
                }
            }

            double subtotal = 0.0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(SUM(qty * unit_price), 0.0) FROM order_items WHERE order_id = ?")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) subtotal = rs.getDouble(1);
                }
            }

            double gst = Math.round(subtotal * 0.05 * 100.0) / 100.0;
            double total = Math.round((subtotal + gst) * 100.0) / 100.0;

            try (PreparedStatement updOrder = conn.prepareStatement(
                    "UPDATE orders SET status = 'PAID', subtotal = ?, gst = ?, total = ?, closed_at = ? WHERE id = ?")) {
                updOrder.setDouble(1, subtotal);
                updOrder.setDouble(2, gst);
                updOrder.setDouble(3, total);
                updOrder.setString(4, Instant.now().toString());
                updOrder.setLong(5, orderId);
                updOrder.executeUpdate();
            }

            try (PreparedStatement markServed = conn.prepareStatement(
                    "UPDATE order_items SET kitchen_status = 'SERVED' WHERE order_id = ?")) {
                markServed.setLong(1, orderId);
                markServed.executeUpdate();
            }

            try (PreparedStatement updTable = conn.prepareStatement(
                    "UPDATE dining_tables SET status = 'VACANT', active_order_id = NULL WHERE table_code = ?")) {
                updTable.setString(1, tableCode);
                updTable.executeUpdate();
            }

            conn.commit();
            return String.format(
                "{\"ok\":true,\"receipt\":{\"orderId\":%d,\"tableCode\":\"%s\",\"subtotal\":%.2f,\"gst\":%.2f,\"total\":%.2f}}",
                orderId, JsonUtil.escape(tableCode), subtotal, gst, total
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String getDashboardStateJson() throws SQLException {
        int activeTables = 0, totalTables = 0, kitchenPending = 0, readyToServe = 0;
        List<String> tableJsons = new ArrayList<>();

        String tSql = """
            SELECT t.table_code, t.zone, t.capacity, t.status, t.active_order_id,
                   COALESCE((SELECT SUM(qty * unit_price) FROM order_items oi WHERE oi.order_id = t.active_order_id), 0.0) AS running_subtotal
            FROM dining_tables t
            ORDER BY t.table_code ASC
        """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(tSql)) {
            while (rs.next()) {
                totalTables++;
                String status = rs.getString("status");
                if ("DINING".equals(status)) activeTables++;
                long orderId = rs.getLong("active_order_id");

                List<String> itemsForOrder = new ArrayList<>();
                if (orderId > 0) {
                    try (PreparedStatement ips = conn.prepareStatement(
                            "SELECT id, item_name, qty, unit_price, kitchen_status FROM order_items WHERE order_id = ? ORDER BY id ASC")) {
                        ips.setLong(1, orderId);
                        try (ResultSet irs = ips.executeQuery()) {
                            while (irs.next()) {
                                itemsForOrder.add(String.format(
                                    "{\"id\":%d,\"name\":\"%s\",\"qty\":%d,\"unitPrice\":%.2f,\"status\":\"%s\"}",
                                    irs.getLong("id"),
                                    JsonUtil.escape(irs.getString("item_name")),
                                    irs.getInt("qty"),
                                    irs.getDouble("unit_price"),
                                    JsonUtil.escape(irs.getString("kitchen_status"))
                                ));
                            }
                        }
                    }
                }

                tableJsons.add(String.format(
                    "{\"tableCode\":\"%s\",\"zone\":\"%s\",\"capacity\":%d,\"status\":\"%s\",\"orderId\":%d,\"runningSubtotal\":%.2f,\"items\":[%s]}",
                    JsonUtil.escape(rs.getString("table_code")),
                    JsonUtil.escape(rs.getString("zone")),
                    rs.getInt("capacity"),
                    JsonUtil.escape(status),
                    orderId,
                    rs.getDouble("running_subtotal"),
                    String.join(",", itemsForOrder)
                ));
            }
        }

        List<String> kdsJsons = new ArrayList<>();
        String kSql = """
            SELECT id, order_id, table_code, item_name, qty, prep_mins, kitchen_status, ordered_at_ms
            FROM order_items
            WHERE kitchen_status IN ('QUEUED', 'PREPARING', 'READY')
            ORDER BY ordered_at_ms ASC
        """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(kSql)) {
            while (rs.next()) {
                String ks = rs.getString("kitchen_status");
                if ("READY".equals(ks)) readyToServe++;
                else kitchenPending++;

                kdsJsons.add(String.format(
                    "{\"kotId\":%d,\"orderId\":%d,\"tableCode\":\"%s\",\"itemName\":\"%s\",\"qty\":%d,\"prepMins\":%d,\"status\":\"%s\",\"orderedAtMs\":%d}",
                    rs.getLong("id"),
                    rs.getLong("order_id"),
                    JsonUtil.escape(rs.getString("table_code")),
                    JsonUtil.escape(rs.getString("item_name")),
                    rs.getInt("qty"),
                    rs.getInt("prep_mins"),
                    JsonUtil.escape(ks),
                    rs.getLong("ordered_at_ms")
                ));
            }
        }

        List<String> menuJsons = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, category, price, prep_mins FROM menu_items ORDER BY id ASC")) {
            while (rs.next()) {
                menuJsons.add(String.format(
                    "{\"id\":%d,\"name\":\"%s\",\"category\":\"%s\",\"price\":%.2f,\"prepMins\":%d}",
                    rs.getInt("id"),
                    JsonUtil.escape(rs.getString("name")),
                    JsonUtil.escape(rs.getString("category")),
                    rs.getDouble("price"),
                    rs.getInt("prep_mins")
                ));
            }
        }

        double revenue = 0.0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(total), 0.0) FROM orders WHERE status = 'PAID'")) {
            if (rs.next()) revenue = rs.getDouble(1);
        }

        return String.format(
            "{\"metrics\":{\"activeTables\":%d,\"totalTables\":%d,\"kitchenPending\":%d,\"readyToServe\":%d,\"revenue\":%.2f},\"tables\":[%s],\"kdsTickets\":[%s],\"menu\":[%s]}",
            activeTables, totalTables, kitchenPending, readyToServe, revenue,
            String.join(",", tableJsons),
            String.join(",", kdsJsons),
            String.join(",", menuJsons)
        );
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
