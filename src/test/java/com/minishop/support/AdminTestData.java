package com.minishop.support;

import com.minishop.user.Role;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Data owned by the GĐ6 admin integration tests on a real database (QĐ14 of the phase 6 plan): users with
 * the email suffix {@code @admin.test}, categories and products whose name starts with {@code Admin test },
 * and the upload files those tests cause. It never touches the seeded accounts, categories or products.
 * {@link #cleanUp()} removes everything in foreign-key order: order_items, orders, cart_items, carts,
 * products, categories, users.
 */
public final class AdminTestData {

    public static final String PASSWORD = "Test-pass-1";
    public static final String NAME_PREFIX = "Admin test ";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Path uploadDir;
    private final List<Path> trackedUploads = new ArrayList<>();

    /** @param uploadDir the configured upload directory (absolute, normalized); files written there are removed too */
    public AdminTestData(JdbcTemplate jdbc, PasswordEncoder encoder, Path uploadDir) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.uploadDir = uploadDir;
    }

    public record TestUser(long id, String email, String fullName) {
    }

    // ------------------------------------------------------------------ users

    public TestUser createUser(Role role) {
        String email = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@admin.test";
        String fullName = role == Role.ADMIN ? "Admin Tester" : "Customer Tester";
        Long id = jdbc.queryForObject(
                "select id from final table (insert into users (email, password_hash, full_name, role) values (?, ?, ?, ?))",
                Long.class, email, encoder.encode(PASSWORD), fullName, role.name());
        return new TestUser(id, email, fullName);
    }

    // ------------------------------------------------------------- categories

    public long createCategory(String suffix) {
        return jdbc.queryForObject("select id from final table (insert into categories (name) values (?))", Long.class,
                uniqueName(suffix));
    }

    public String categoryName(long categoryId) {
        return jdbc.queryForObject("select name from categories where id = ?", String.class, categoryId);
    }

    public boolean categoryExists(long categoryId) {
        return jdbc.queryForObject("select count(*) from categories where id = ?", Integer.class, categoryId) > 0;
    }

    public int categoryCountByName(String name) {
        return jdbc.queryForObject("select count(*) from categories where name = ?", Integer.class, name);
    }

    /** Test categories whose name equals one of the cases of {@code name} (case-insensitive). */
    public int categoryCountIgnoringCase(String name) {
        return jdbc.queryForObject("select count(*) from categories where lower(name) = lower(?)", Integer.class,
                name);
    }

    // --------------------------------------------------------------- products

    public long createProduct(long categoryId, String suffix, long price, int stock, boolean active) {
        return jdbc.queryForObject(
                "select id from final table (insert into products (category_id, name, price, stock, active) values (?, ?, ?, ?, ?))",
                Long.class, categoryId, uniqueName(suffix), price, stock, active);
    }

    public int stock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    public void setStock(long productId, int stock) {
        jdbc.update("update products set stock = ? where id = ?", stock, productId);
    }

    public void setActive(long productId, boolean active) {
        jdbc.update("update products set active = ? where id = ?", active, productId);
    }

    public void setImagePath(long productId, String imagePath) {
        jdbc.update("update products set image_path = ? where id = ?", imagePath, productId);
    }

    public String productName(long productId) {
        return jdbc.queryForObject("select name from products where id = ?", String.class, productId);
    }

    public Map<String, Object> productRow(long productId) {
        return jdbc.queryForMap("select * from products where id = ?", productId);
    }

    public boolean productExists(long productId) {
        return jdbc.queryForObject("select count(*) from products where id = ?", Integer.class, productId) > 0;
    }

    /** Products of a category (hidden ones included). */
    public int productCount(long categoryId) {
        return jdbc.queryForObject("select count(*) from products where category_id = ?", Integer.class, categoryId);
    }

    /** Products whose category row no longer exists (must always be zero). */
    public int orphanedProductCount() {
        return jdbc.queryForObject("select count(*) from products p where not exists "
                + "(select 1 from categories c where c.id = p.category_id)", Integer.class);
    }

    // ------------------------------------------------------------------ carts

    /** A DB cart built with SQL; {@code lines} are {productId, quantity} pairs. */
    public void createCart(long userId, long[]... lines) {
        Long cartId = jdbc.queryForObject("select id from final table (insert into carts (user_id) values (?))", Long.class, userId);
        for (long[] line : lines) {
            jdbc.update("insert into cart_items (cart_id, product_id, quantity) values (?, ?, ?)",
                    cartId, line[0], (int) line[1]);
        }
    }

    public Map<Long, Integer> cartLines(long userId) {
        Map<Long, Integer> result = new TreeMap<>();
        jdbc.query("select i.product_id, i.quantity from cart_items i join carts c on c.id = i.cart_id "
                + "where c.user_id = ?", rs -> {
            result.put(rs.getLong(1), rs.getInt(2));
        }, userId);
        return result;
    }

    // ----------------------------------------------------------------- orders

    /**
     * Inserts an order straight into the tables (no service, stock untouched) with the name and price snapshot
     * taken from the products now. {@code items} are {productId, quantity} pairs.
     */
    public long insertOrder(long userId, String status, long[]... items) {
        BigDecimal total = BigDecimal.ZERO;
        for (long[] item : items) {
            BigDecimal price = jdbc.queryForObject("select price from products where id = ?", BigDecimal.class, item[0]);
            total = total.add(price.multiply(BigDecimal.valueOf(item[1])));
        }
        long orderId = insertOrderWithTotal(userId, status, total);
        for (long[] item : items) {
            jdbc.update("insert into order_items (order_id, product_id, product_name, unit_price, quantity) "
                    + "select ?, p.id, p.name, p.price, ? from products p where p.id = ?", orderId, (int) item[1], item[0]);
        }
        return orderId;
    }

    /** An order header with a given total and no lines (enough for dashboard and list tests). */
    public long insertOrderWithTotal(long userId, String status, BigDecimal total) {
        return jdbc.queryForObject(
                "select id from final table (insert into orders (user_id, status, receiver_name, phone, shipping_address, payment_method, total_amount) "
                        + "values (?, ?, 'Người nhận', '0912345678', 'Địa chỉ', 'COD', ?))",
                Long.class, userId, status, total);
    }

    public void setOrderCreatedAt(long userId, Instant createdAt) {
        jdbc.update("update orders set created_at = ? where user_id = ?", Timestamp.from(createdAt), userId);
    }

    public String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    public Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("select * from orders where id = ?", orderId);
    }

    public int orderCount(long userId) {
        return jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, userId);
    }

    /** Order ids of the user, newest (largest id) first. */
    public List<Long> orderIds(long userId) {
        return jdbc.queryForList("select id from orders where user_id = ? order by id desc", Long.class, userId);
    }

    // ---------------------------------------------------------------- uploads

    /** Remembers a file (absolute path) to delete in {@link #cleanUp()}. */
    public void trackUpload(Path file) {
        trackedUploads.add(file);
    }

    /** The file an {@code image_path} such as {@code /uploads/{uuid}.png} refers to. */
    public Path uploadFile(String imagePath) {
        return uploadDir.resolve(imagePath.substring("/uploads/".length()));
    }

    public Path uploadDir() {
        return uploadDir;
    }

    // ------------------------------------------------------------ concurrency

    /** What a raced task returned or threw; exceptions are collected so the test checks them itself. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean failed() {
            return error != null;
        }
    }

    /** Starts all tasks together (latches), waits for all of them and returns their outcomes in order. */
    public static List<Outcome<Object>> race(Callable<?>... tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Outcome<Object>>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return new Outcome<Object>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<Object>(null, t);
                    }
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("race tasks did not start in time");
            }
            go.countDown();
            List<Outcome<Object>> results = new ArrayList<>();
            for (Future<Outcome<Object>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Number of JDBC statements Hibernate prepared while {@code action} ran. */
    public static long statementsOf(EntityManagerFactory entityManagerFactory, Runnable action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            action.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    // ---------------------------------------------------------------- cleanup

    public void cleanUp() {
        String testUsers = "select id from users where email like '%@admin.test'";
        String testProducts = "select id from products where name like '" + NAME_PREFIX + "%'";
        List<String> uploads = jdbc.queryForList("select image_path from products where name like '" + NAME_PREFIX
                + "%' and image_path like '/uploads/%'", String.class);

        jdbc.update("delete from order_items where order_id in (select id from orders where user_id in (" + testUsers + "))");
        jdbc.update("delete from order_items where product_id in (" + testProducts + ")");
        jdbc.update("delete from orders where user_id in (" + testUsers + ")");
        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id in (" + testUsers + "))");
        jdbc.update("delete from cart_items where product_id in (" + testProducts + ")");
        jdbc.update("delete from carts where user_id in (" + testUsers + ")");
        jdbc.update("delete from products where name like '" + NAME_PREFIX + "%'");
        jdbc.update("delete from categories where name like '" + NAME_PREFIX + "%'");
        jdbc.update("delete from users where email like '%@admin.test'");

        for (String imagePath : uploads) {
            deleteQuietly(uploadFile(imagePath));
        }
        for (Path file : trackedUploads) {
            deleteQuietly(file);
        }
        trackedUploads.clear();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a leftover file under target/ is harmless
        }
    }

    private static String uniqueName(String suffix) {
        return NAME_PREFIX + suffix + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
