package com.minishop.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Dữ liệu riêng của test đặt hàng trên PostgreSQL thật (QĐ14 plan GĐ5): người dùng email đuôi
 * {@code @order.test}, sản phẩm tên bắt đầu bằng {@code Order test }, danh mục lấy từ seed. Không bao giờ
 * sửa tài khoản hay sản phẩm seed. Dọn theo thứ tự order_items, orders, cart_items, carts, sản phẩm, người dùng.
 */
final class OrderTestData {

    static final String PASSWORD = "Test-pass-1";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    OrderTestData(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    record TestUser(long id, String email) {
    }

    TestUser createUser() {
        String email = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@order.test";
        Long id = jdbc.queryForObject(
                "insert into users (email, password_hash, full_name, role) values (?, ?, 'Order Tester', 'USER') returning id",
                Long.class, email, encoder.encode(PASSWORD));
        return new TestUser(id, email);
    }

    long createProduct(String suffix, long price, int stock) {
        Long categoryId = jdbc.queryForObject("select min(id) from categories", Long.class);
        return jdbc.queryForObject(
                "insert into products (category_id, name, price, stock, active) values (?, ?, ?, ?, true) returning id",
                Long.class, categoryId, "Order test " + suffix + " " + UUID.randomUUID().toString().substring(0, 8),
                price, stock);
    }

    String productName(long productId) {
        return jdbc.queryForObject("select name from products where id = ?", String.class, productId);
    }

    void setActive(long productId, boolean active) {
        jdbc.update("update products set active = ? where id = ?", active, productId);
    }

    void setStock(long productId, int stock) {
        jdbc.update("update products set stock = ? where id = ?", stock, productId);
    }

    void setPrice(long productId, long price) {
        jdbc.update("update products set price = ? where id = ?", price, productId);
    }

    void setName(long productId, String name) {
        jdbc.update("update products set name = ? where id = ?", name, productId);
    }

    int stock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    /** Dựng giỏ DB bằng SQL; {@code lines} là cặp {productId, quantity}, thêm theo thứ tự đã cho. */
    void createCart(long userId, long[]... lines) {
        Long cartId = jdbc.queryForObject("insert into carts (user_id) values (?) returning id", Long.class, userId);
        for (long[] line : lines) {
            jdbc.update("insert into cart_items (cart_id, product_id, quantity) values (?, ?, ?)",
                    cartId, line[0], (int) line[1]);
        }
    }

    /** @return productId -> quantity của giỏ DB của user, sắp theo productId */
    Map<Long, Integer> cartLines(long userId) {
        Map<Long, Integer> result = new TreeMap<>();
        jdbc.query("select i.product_id, i.quantity from cart_items i join carts c on c.id = i.cart_id "
                + "where c.user_id = ?", rs -> {
            result.put(rs.getLong(1), rs.getInt(2));
        }, userId);
        return result;
    }

    /**
     * Chèn thẳng một đơn (không qua dịch vụ, không đụng kho) với tên và giá chụp từ sản phẩm lúc này.
     * {@code items} là cặp {productId, quantity}.
     */
    long insertOrder(long userId, String status, long[]... items) {
        BigDecimal total = BigDecimal.ZERO;
        for (long[] item : items) {
            BigDecimal price = jdbc.queryForObject("select price from products where id = ?", BigDecimal.class, item[0]);
            total = total.add(price.multiply(BigDecimal.valueOf(item[1])));
        }
        Long orderId = jdbc.queryForObject(
                "insert into orders (user_id, status, receiver_name, phone, shipping_address, payment_method, total_amount) "
                        + "values (?, ?, 'Người nhận', '0912345678', 'Địa chỉ', 'COD', ?) returning id",
                Long.class, userId, status, total);
        for (long[] item : items) {
            jdbc.update("insert into order_items (order_id, product_id, product_name, unit_price, quantity) "
                    + "select ?, p.id, p.name, p.price, ? from products p where p.id = ?", orderId, (int) item[1], item[0]);
        }
        return orderId;
    }

    int orderCount(long userId) {
        return jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, userId);
    }

    /** @return ids của đơn của user, mới nhất (id lớn nhất) trước */
    List<Long> orderIds(long userId) {
        return jdbc.queryForList("select id from orders where user_id = ? order by id desc", Long.class, userId);
    }

    Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("select * from orders where id = ?", orderId);
    }

    String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    /** @return các dòng của đơn (product_id, product_name, unit_price, quantity) theo id */
    List<Map<String, Object>> orderItems(long orderId) {
        return jdbc.queryForList("select product_id, product_name, unit_price, quantity from order_items "
                + "where order_id = ? order by id", orderId);
    }

    void cleanUp() {
        String testUsers = "select id from users where email like '%@order.test'";
        String testProducts = "select id from products where name like 'Order test %'";
        jdbc.update("delete from order_items where order_id in (select id from orders where user_id in (" + testUsers + "))");
        jdbc.update("delete from order_items where product_id in (" + testProducts + ")");
        jdbc.update("delete from orders where user_id in (" + testUsers + ")");
        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id in (" + testUsers + "))");
        jdbc.update("delete from cart_items where product_id in (" + testProducts + ")");
        jdbc.update("delete from carts where user_id in (" + testUsers + ")");
        jdbc.update("delete from products where name like 'Order test %'");
        jdbc.update("delete from users where email like '%@order.test'");
    }
}
