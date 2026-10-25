package com.minishop.cart;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

/**
 * Dữ liệu riêng của test giỏ trên PostgreSQL thật (QĐ12): người dùng email đuôi {@code @cart.test}, sản phẩm tên
 * bắt đầu bằng {@code Cart test }, danh mục lấy từ seed. Không bao giờ sửa tài khoản hay sản phẩm seed.
 */
final class CartTestData {

    static final String PASSWORD = "Test-pass-1";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    CartTestData(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /** @return {id, email} */
    record TestUser(long id, String email) {
    }

    TestUser createUser() {
        String email = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@cart.test";
        Long id = jdbc.queryForObject(
                "select id from final table (insert into users (email, password_hash, full_name, role) values (?, ?, 'Cart Tester', 'USER'))",
                Long.class, email, encoder.encode(PASSWORD));
        return new TestUser(id, email);
    }

    long createProduct(String suffix, long price, int stock) {
        Long categoryId = jdbc.queryForObject("select min(id) from categories", Long.class);
        Long id = jdbc.queryForObject(
                "select id from final table (insert into products (category_id, name, price, stock, active) values (?, ?, ?, ?, true))",
                Long.class, categoryId, "Cart test " + suffix + " " + UUID.randomUUID().toString().substring(0, 8),
                price, stock);
        return id;
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

    /** Dựng giỏ DB trực tiếp bằng SQL; {@code lines} là cặp {productId, quantity}. */
    void createCart(long userId, long[]... lines) {
        Long cartId = jdbc.queryForObject("select id from final table (insert into carts (user_id) values (?))", Long.class, userId);
        for (long[] line : lines) {
            jdbc.update("insert into cart_items (cart_id, product_id, quantity) values (?, ?, ?)",
                    cartId, line[0], (int) line[1]);
        }
    }

    /** @return productId -> quantity của giỏ DB của user, sắp theo productId */
    java.util.Map<Long, Integer> cartLines(long userId) {
        java.util.Map<Long, Integer> result = new java.util.TreeMap<>();
        jdbc.query("select i.product_id, i.quantity from cart_items i join carts c on c.id = i.cart_id "
                        + "where c.user_id = ?",
                rs -> {
                    result.put(rs.getLong(1), rs.getInt(2));
                }, userId);
        return result;
    }

    int cartCount(long userId) {
        return jdbc.queryForObject("select count(*) from carts where user_id = ?", Integer.class, userId);
    }

    int totalRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    List<String> stockSnapshot() {
        return jdbc.queryForList("select id || ':' || stock from products order by id", String.class);
    }

    /** cart_items -> carts -> sản phẩm test -> người dùng test. */
    void cleanUp() {
        jdbc.update("delete from cart_items where cart_id in (select c.id from carts c join users u on u.id = c.user_id "
                + "where u.email like '%@cart.test')");
        jdbc.update("delete from cart_items where product_id in (select id from products where name like 'Cart test %')");
        jdbc.update("delete from carts where user_id in (select id from users where email like '%@cart.test')");
        jdbc.update("delete from products where name like 'Cart test %'");
        jdbc.update("delete from users where email like '%@cart.test'");
    }
}
