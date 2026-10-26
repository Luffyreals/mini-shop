package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.storage.ImageUpload;
import com.minishop.support.AdminTestData;
import com.minishop.support.TestImages;
import com.minishop.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product administration on H2 (AC-006-17, 19, 22, 23, 25..27, 30, 32, 33, 37, 51): the upload
 * flow through the service and the stored file, the list, the stale-form stock rule (deterministic, no sleeping),
 * image replacement and rollback, hide/show against the storefront, and the stock adjustment at its bounds.
 * No @Transactional (the tests need real commits); only {@link AdminTestData} rows and files under the test
 * upload directory.
 */
@SpringBootTest
class ProductAdminIntegrationTest {

    private static final Pattern UPLOAD_PATH = Pattern.compile("/uploads/[0-9a-f-]{36}\\.png");

    @Autowired
    ProductService productService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ProductAdminService productAdminService;

    @Autowired
    ProductRepository productRepository;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

    Path uploadDir;
    AdminTestData data;
    AdminTestData.TestUser admin;
    long category;

    @BeforeEach
    void setUp() {
        uploadDir = Path.of(uploadDirSetting).toAbsolutePath().normalize();
        data = new AdminTestData(jdbc, encoder, uploadDir);
        data.cleanUp();
        admin = data.createUser(Role.ADMIN);
        category = data.createCategory("Product admin");
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // ---------------------------------------------------------------- helpers

    private static ProductDraft draft(long categoryId, String name, String price) {
        return new ProductDraft(name, categoryId, new BigDecimal(price), null);
    }

    private static ImageUpload png(int bytes) {
        byte[] content = TestImages.png(bytes);
        return ImageUpload.from("a.png", "image/png", content.length, new ByteArrayResource(content)).orElseThrow();
    }

    private Set<Path> filesInUploadDir() throws IOException {
        if (!Files.isDirectory(uploadDir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(uploadDir)) {
            return Set.copyOf(files.toList());
        }
    }


    /** Seed images live under {@code static/} until B2b moves them to the resource root; accept either place. */
    private static Path seedImageFile(String seedPath) {
        return Stream.of(Path.of("src/main/resources/static" + seedPath), Path.of("src/main/resources" + seedPath))
                .map(Path::toAbsolutePath)
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new AssertionError("seed image is in neither resource location: " + seedPath));
    }

    private static boolean seedImageOnClasspath(String seedPath) {
        return new ClassPathResource("static" + seedPath).exists()
                || new ClassPathResource(seedPath.substring(1)).exists();
    }

    private List<Long> adminListIds(String query) {
        AdminProductPage page = query == null ? productAdminService.list(null, null)
                : productAdminService.list(query.split("\\|", -1)[0], query.split("\\|", -1)[1]);
        return page.products().stream().map(AdminProductRow::id).toList();
    }

    // ------------------------------------------------- AC-006-19, 27, 51

    /**
     * Was the web test {@code postProduct_withPng…} and H2DatabaseSmokeTest#admin_fullFlow (product with a PNG). The
     * price notation "1.250.000" is {@code admin.PriceInputTest}; here the parsed 1250000 reaches the service.
     * Serving the file publicly was a web concern and is gone with the web layer.
     */
    @Test
    void create_withPngUpload_createsRowStoresUuidFileAndShowsOnTheStorefront() throws Exception {
        String name = AdminTestData.NAME_PREFIX + "Bình nước 1L";
        byte[] png = TestImages.png(500 * 1024);
        ImageUpload upload = ImageUpload.from("ảnh sản phẩm (1).PNG", "image/png", png.length,
                new ByteArrayResource(png)).orElseThrow();

        SavedProduct saved = productAdminService.create(admin.id(),
                new ProductDraft(name, category, new BigDecimal("1250000"), "Giữ nhiệt 12 giờ"), 30, true, upload);

        assertThat(saved.name()).isEqualTo(name);
        Map<String, Object> row = data.productRow(saved.id());
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("1250000");
        assertThat(row.get("stock")).isEqualTo(30);
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("category_id")).isEqualTo(category);
        assertThat(row.get("description")).isEqualTo("Giữ nhiệt 12 giờ");
        String imagePath = (String) row.get("image_path");
        assertThat(imagePath).matches(UPLOAD_PATH.pattern());
        assertThat(imagePath).doesNotContain("ảnh").doesNotContain("PNG");
        Path file = data.uploadFile(imagePath);
        assertThat(file).exists().hasBinaryContent(png);

        // The storefront reads the new product and points at the stored file.
        ProductDetailView detail = productService.getDetail(saved.id());
        assertThat(detail.name()).isEqualTo(name);
        assertThat(detail.price()).isEqualByComparingTo("1250000");
        assertThat(detail.inStock()).isTrue();
        assertThat(detail.imageUrl()).contains(file.getFileName().toString());
    }

    // ---------------------------------------------------------- AC-006-17

    @Test
    void list_with25ProductsTwoHidden_pagesTwentyNewestFirstIncludingHidden() throws Exception {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            mine.add(data.createProduct(category, "List " + i, 100_000, 5, true));
        }
        long hiddenA = mine.get(24);
        long hiddenB = mine.get(23);
        data.setActive(hiddenA, false);
        data.setActive(hiddenB, false);
        List<Long> newestFirst = new ArrayList<>(mine);
        Collections.reverse(newestFirst);

        // Page 1: my 20 newest, hidden ones included, newest first (created_at DESC, id DESC).
        assertThat(adminListIds(null)).containsExactlyElementsOf(newestFirst.subList(0, 20));
        assertThat(adminListIds(null)).contains(hiddenA, hiddenB);
        // Page 2 starts with my 5 oldest, then the seed products.
        assertThat(adminListIds("|2").subList(0, 5)).containsExactlyElementsOf(newestFirst.subList(20, 25));
        // A bad page is page 1.
        assertThat(adminListIds("|abc")).containsExactlyElementsOf(newestFirst.subList(0, 20));

        assertThat(productAdminService.list(null, null).products()).filteredOn(r -> r.id() == hiddenA)
                .singleElement().satisfies(r -> assertThat(r.active()).isFalse());
        assertThat(productAdminService.list(null, null).products()).filteredOn(r -> r.id() == newestFirst.get(2))
                .singleElement().satisfies(r -> assertThat(r.active()).isTrue());
    }

    @Test
    void list_search_isCaseInsensitiveAndEscapesLikeWildcards() throws Exception {
        long plain = data.createProduct(category, "áo-search", 100_000, 5, true);
        long percent = data.createProduct(category, "100% cotton", 100_000, 5, true);
        long underscore = data.createProduct(category, "under_score", 100_000, 5, true);

        assertThat(adminListIds("áo-search|")).containsExactly(plain);
        assertThat(adminListIds("ÁO-SEARCH|")).containsExactly(plain);
        assertThat(adminListIds("%|")).containsExactly(percent);
        assertThat(adminListIds("_|")).containsExactly(underscore);
        assertThat(productAdminService.list("không-có-sản-phẩm-nào-như-vậy", null).isEmpty()).isTrue();
        assertThat(productAdminService.list("%", null).products()).extracting(AdminProductRow::name)
                .containsExactly(data.productName(percent));
    }

    // ---------------------------------------------------------- AC-006-23

    /**
     * The form was opened when stock was 10; before the admin saves, a customer order takes 2 (another
     * connection commits stock = 8). The save runs inside the same transaction that is still open, so the
     * order of events is fixed: read, concurrent commit, flush. The UPDATE of the product must not write stock.
     */
    @Test
    void update_savedAfterConcurrentStockChange_doesNotOverwriteStock() throws Exception {
        long product = data.createProduct(category, "Stale form", 100_000, 10, true);
        String newName = AdminTestData.NAME_PREFIX + "Renamed " + product;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            productAdminService.update(admin.id(), product, draft(category, newName, "150000"), null);
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(true);
                try (PreparedStatement st = other.prepareStatement(
                        "update products set stock = stock - 2 where id = ?")) {
                    st.setLong(1, product);
                    assertThat(st.executeUpdate()).isEqualTo(1);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(data.stock(product)).isEqualTo(8);
        assertThat(data.productName(product)).isEqualTo(newName);
        assertThat((BigDecimal) data.productRow(product).get("price")).isEqualByComparingTo("150000");
    }

    /**
     * {@code @DynamicUpdate}: the edit loaded the product as active; before it flushes, another transaction commits
     * a hide. The edit's UPDATE writes only the changed columns (name, price...), so the hide is not undone and the
     * stock is not touched. Deterministic: read, concurrent commit, flush.
     */
    @Test
    void update_savedAfterConcurrentHide_doesNotUndoTheHideAndLeavesStockAlone() throws Exception {
        long product = data.createProduct(category, "Stale active", 100_000, 10, true);
        String newName = AdminTestData.NAME_PREFIX + "Renamed hidden " + product;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            productAdminService.update(admin.id(), product, draft(category, newName, "150000"), null);
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(true);
                try (PreparedStatement st = other.prepareStatement("update products set active = false where id = ?")) {
                    st.setLong(1, product);
                    assertThat(st.executeUpdate()).isEqualTo(1);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(data.productRow(product).get("active")).isEqualTo(false);
        assertThat(data.productName(product)).isEqualTo(newName);
        assertThat((BigDecimal) data.productRow(product).get("price")).isEqualByComparingTo("150000");
        assertThat(data.stock(product)).isEqualTo(10);
    }

    // ---------------------------------------------------------- AC-006-30

    @Test
    void update_withNewImage_deletesOldUploadAfterCommitAndKeepsNewOne() throws Exception {
        String name = AdminTestData.NAME_PREFIX + "Replace image";
        SavedProduct saved = productAdminService.create(admin.id(), draft(category, name, "100000"), 5, true, png(200));
        String oldPath = (String) data.productRow(saved.id()).get("image_path");
        assertThat(data.uploadFile(oldPath)).exists();

        productAdminService.update(admin.id(), saved.id(), draft(category, name, "100000"), png(300));

        String newPath = (String) data.productRow(saved.id()).get("image_path");
        assertThat(newPath).isNotEqualTo(oldPath).matches(UPLOAD_PATH.pattern());
        assertThat(data.uploadFile(oldPath)).doesNotExist();
        assertThat(data.uploadFile(newPath)).exists();
    }

    @Test
    void update_replacingSeedImage_neverTouchesStaticFiles() throws Exception {
        long product = data.createProduct(category, "Seed image", 100_000, 5, true);
        String seedPath = "/images/products/ao-thun-cotton-co-tron.jpg";
        data.setImagePath(product, seedPath);
        Path seedFile = seedImageFile(seedPath);
        assertThat(seedFile).exists();
        Set<Path> uploadsBefore = filesInUploadDir();

        productAdminService.update(admin.id(), product, draft(category, data.productName(product), "100000"), png(200));

        String newPath = (String) data.productRow(product).get("image_path");
        assertThat(newPath).matches(UPLOAD_PATH.pattern());
        assertThat(seedFile).exists();
        assertThat(seedImageOnClasspath(seedPath)).isTrue();
        assertThat(filesInUploadDir()).hasSize(uploadsBefore.size() + 1).contains(data.uploadFile(newPath));
    }

    @Test
    void update_withImageThenRollback_removesNewFileAndKeepsOldImagePath() throws Exception {
        long product = data.createProduct(category, "Rollback image", 100_000, 5, true);
        String seedPath = "/images/products/ao-thun-cotton-co-tron.jpg";
        data.setImagePath(product, seedPath);
        Set<Path> before = filesInUploadDir();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            productAdminService.update(admin.id(), product, draft(category, data.productName(product), "100000"), png(200));
            try {
                assertThat(filesInUploadDir()).as("the new file exists until the rollback").hasSize(before.size() + 1);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            status.setRollbackOnly();
        });

        assertThat(filesInUploadDir()).isEqualTo(before);
        assertThat(data.productRow(product).get("image_path")).isEqualTo(seedPath);
    }

    // ---------------------------------------------------------- AC-006-22

    /**
     * Was a web test through the cart and order pages. The cart shows current prices (CartIntegrationTest#
     * view_afterPriceChange_showsNewPriceAndTotal) and the order shows its snapshot through OrderService
     * (OrderIntegrationTest#getOrder_afterProductPriceAndNameChange_stillShowsSnapshot); here the edit itself:
     * the storefront shows the new data and the stored order lines are not touched.
     */
    @Test
    void update_nameAndPrice_showOnStorefrontButOldOrderLineKeepsSnapshot() {
        var customer = data.createUser(Role.USER);
        long product = data.createProduct(category, "Before edit", 100_000, 10, true);
        String oldName = data.productName(product);
        long orderId = data.insertOrder(customer.id(), "PENDING", new long[]{product, 1});
        String newName = AdminTestData.NAME_PREFIX + "After edit " + product;

        String saved = productAdminService.update(admin.id(), product,
                new ProductDraft(newName, category, new BigDecimal("150000"), "Mới"), null);

        assertThat(saved).isEqualTo(newName);
        ProductDetailView detail = productService.getDetail(product);
        assertThat(detail.name()).isEqualTo(newName);
        assertThat(detail.price()).isEqualByComparingTo("150000");
        assertThat(detail.description()).isEqualTo("Mới");
        Map<String, Object> line = jdbc.queryForMap(
                "select product_name, unit_price from order_items where order_id = ?", orderId);
        assertThat(line.get("product_name")).isEqualTo(oldName);
        assertThat((BigDecimal) line.get("unit_price")).isEqualByComparingTo("100000");
    }

    // ---------------------------------------------------------- AC-006-25

    /** The cart line of a hidden product is labelled discontinued: CartIntegrationTest#view_withHidden…. */
    @Test
    void setActive_hideThenShow_removesProductFromStorefrontAndKeepsCartLines() {
        var customer = data.createUser(Role.USER);
        long product = data.createProduct(category, "Hide me", 100_000, 10, true);
        data.createCart(customer.id(), new long[]{product, 1});
        String name = data.productName(product);

        assertThat(productAdminService.setActive(admin.id(), product, false)).isEqualTo(name);

        assertThat(data.productRow(product).get("active")).isEqualTo(false);
        assertThatThrownBy(() -> productService.getDetail(product)).isInstanceOf(NotFoundException.class);
        assertThat(data.cartLines(customer.id())).containsExactly(Map.entry(product, 1));

        assertThat(productAdminService.setActive(admin.id(), product, true)).isEqualTo(name);

        assertThat(productService.getDetail(product).name()).isEqualTo(name);
    }

    // -------------------------------------------------- AC-006-32, 33, 37

    @Test
    void adjustStock_plusFive_updatesStockAndReportsNewValue() {
        long product = data.createProduct(category, "Stock plus", 100_000, 10, true);
        String name = data.productName(product);

        assertThat(productAdminService.adjustStock(admin.id(), product, 5))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 15));

        assertThat(data.stock(product)).isEqualTo(15);
    }

    @Test
    void adjustStock_minusTwoFromThree_thenMinusFive_leavesOneAndReportsNotEnough() {
        long product = data.createProduct(category, "Stock minus", 100_000, 3, true);
        String name = data.productName(product);

        assertThat(productAdminService.adjustStock(admin.id(), product, -2))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 1));
        assertThat(productAdminService.adjustStock(admin.id(), product, -5))
                .isEqualTo(new StockAdjustResult.NotEnough(name, 5, 1));
        assertThat(data.stock(product)).isEqualTo(1);
    }

    @Test
    void adjustStock_onHiddenProduct_stillAdjusts() {
        long product = data.createProduct(category, "Hidden stock", 100_000, 4, false);
        String name = data.productName(product);

        assertThat(productAdminService.adjustStock(admin.id(), product, 6))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 10));
        assertThat(data.stock(product)).isEqualTo(10);
    }

    /** schema-guardian: the conditional UPDATE at both ends of 0..100000, on the real database. */
    @Test
    void adjustStock_atTheBounds_followsTheBetweenCondition() {
        long product = data.createProduct(category, "Bounds", 100_000, 0, true);
        String name = data.productName(product);

        assertThat(productAdminService.adjustStock(admin.id(), product, -1))
                .isEqualTo(new StockAdjustResult.NotEnough(name, 1, 0));
        assertThat(data.stock(product)).isZero();
        assertThat(productAdminService.adjustStock(admin.id(), product, 100_000))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 100_000));
        assertThat(productAdminService.adjustStock(admin.id(), product, 1))
                .isEqualTo(new StockAdjustResult.WouldExceed(name));
        assertThat(data.stock(product)).isEqualTo(100_000);
        assertThat(productAdminService.adjustStock(admin.id(), product, -100_000))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 0));
        assertThat(data.stock(product)).isZero();
    }

    @Test
    void adjustStock_atNinetyNineThousandNineHundredNinetyNine_plusOneIsAllowedPlusTwoIsNot() {
        long product = data.createProduct(category, "Near top", 100_000, 99_999, true);
        String name = data.productName(product);

        assertThat(productAdminService.adjustStock(admin.id(), product, 2))
                .isEqualTo(new StockAdjustResult.WouldExceed(name));
        assertThat(data.stock(product)).isEqualTo(99_999);
        assertThat(productAdminService.adjustStock(admin.id(), product, 1))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 100_000));
    }
}
