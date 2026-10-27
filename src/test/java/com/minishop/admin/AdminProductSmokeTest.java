package com.minishop.admin;

import com.minishop.catalog.CatalogRoute;
import com.minishop.support.AdminTestData;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestImages;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import javafx.application.Application;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testfx.api.FxToolkit;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product screens on the real JavaFX toolkit (AC-006-17 to -22, -25 to -29, -32 to -34, -37, AC-009-13; D5): the
 * real app with an in-memory database and the seed. The Windows file chooser cannot be driven without a robot, so
 * {@link StubImageFileDialog} replaces the {@code ImageFileDialog} bean and returns a file the test prepared in a
 * temp folder. Everything else is the real screens, driven by firing buttons and looking up nodes. No robot.
 *
 * <p>Products and categories this class creates have names starting with {@code Admin test } and are removed after
 * each test together with the upload files they caused (under {@code target/test-uploads}), so the seed is left as
 * found.
 */
@Tag("ui")
class AdminProductSmokeTest {

    private static final String MSG_IMAGE_TYPE = "Ảnh chỉ được là JPG, PNG hoặc WebP.";
    private static final String MSG_IMAGE_SIZE = "Ảnh tối đa 2 MB.";

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;
    private static AdminSmoke smoke;
    private static JdbcTemplate jdbc;
    private static AdminTestData data;
    private static StubImageFileDialog chooser;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(() -> new TestShellApp(StubImageFileDialog.class));
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
        smoke = new AdminSmoke(ui);
        jdbc = new JdbcTemplate(ui.bean(DataSource.class));
        data = new AdminTestData(jdbc, null, Path.of("target", "test-uploads").toAbsolutePath().normalize());
        chooser = ui.bean(StubImageFileDialog.class);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (app != null && app.context() != null && app.context().isActive()) {
            FxToolkit.cleanupApplication((Application) app);
        }
        FxTestSupport.hideAllWindows();
    }

    @AfterEach
    void backToTheHomeScreen() {
        chooser.reset();
        ui.resetToHome();
        data.cleanUp();
    }

    // ------------------------------------------------------------------ helpers

    private static Path realPng(String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        BufferedImage image = new BufferedImage(40, 50, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 50; y++) {
                image.setRGB(x, y, (x * 6) << 16 | (y * 5) << 8 | 0x80);
            }
        }
        assertThat(ImageIO.write(image, "png", file.toFile())).isTrue();
        return file;
    }

    private static Path fileWith(String fileName, byte[] bytes) throws IOException {
        return Files.write(tempDir.resolve(fileName), bytes);
    }

    private static String newName(String label) {
        return AdminTestData.NAME_PREFIX + label + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private static void loginAndOpenProducts() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("products", "adminProductsScreen");
        FxTestSupport.await("the product list", () -> ui.shownInContent("productRows"));
    }

    private static void searchFor(String name) {
        smoke.typeInContent("productSearchField", name);
        smoke.fireInContent("productSearchButton");
    }

    private static void awaitProductRow(long id) {
        FxTestSupport.await("the row of product " + id, () -> ui.inContent("productRow-" + id) != null);
    }

    private static void openNewProductForm() {
        smoke.fireInContent("addProduct");
        smoke.awaitScreen("productFormScreen");
        smoke.awaitText("the form title", "productFormTitle", "Thêm sản phẩm");
        smoke.node("productNameField");
        FxTestSupport.await("the form to load", () -> ui.shownInContent("saveProduct")
                && FxTestSupport.callOnFx(() -> !((javafx.scene.control.ComboBox<?>) ui.inContent(
                        "productCategoryField")).getItems().isEmpty()));
    }

    private static void openEditForm(long productId) {
        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(AdminProductFormRoute.edit(productId,
                AdminProductsRoute.first())));
        smoke.awaitScreen("productFormScreen");
        smoke.awaitText("the form title", "productFormTitle", "Sửa sản phẩm");
        FxTestSupport.await("the form to load", () -> ui.shownInContent("stockCurrent"));
    }

    private static void fillNewProduct(String name, String price, String stock) {
        smoke.typeInContent("productNameField", name);
        smoke.selectCategory("Nhà cửa");
        smoke.typeInContent("productPriceField", price);
        smoke.typeInContent("productInitialStockField", stock);
    }

    private static void chooseImage(Path file) {
        chooser.willChoose(file);
        smoke.fireInContent("chooseImage");
    }

    private static void awaitCatalogCard(long id, boolean expected, String name) {
        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(CatalogRoute.search(name)));
        smoke.awaitScreen("catalogScreen");
        if (expected) {
            FxTestSupport.await("the card of the product in the shop", () -> ui.inContent("productCard-" + id) != null);
        } else {
            FxTestSupport.await("the empty result of the shop", () -> ui.shownInContent("emptyState"));
            assertThat(ui.inContent("productCard-" + id)).as("a hidden product is not in the shop").isNull();
        }
    }

    private static long categoryOfTests() {
        return data.createCategory("Shelf");
    }

    // ------------------------------------------------------------------ list (AC-006-17)

    @Test
    void productList_showsAPageOfTwentyWithTheHiddenProductsAndTheirFlags() {
        long categoryId = categoryOfTests();
        long hiddenId = data.createProduct(categoryId, "hidden lamp", 250_000, 7, false);
        loginAndOpenProducts();

        searchFor(data.productName(hiddenId));

        awaitProductRow(hiddenId);
        assertThat(ui.labelsUnder(ui.inContentRequired("productRow-" + hiddenId)))
                .contains(data.productName(hiddenId), "250.000 ₫", "7", "Đã ẩn", "Sửa", "Hiện lại");
        assertThat(ui.inContent("showProduct-" + hiddenId)).isNotNull();
        assertThat(ui.inContent("hideProduct-" + hiddenId)).isNull();
        ui.assertNoErrorDialog();
    }

    @Test
    void productList_searchWithNoMatch_showsTheEmptyMessage() {
        loginAndOpenProducts();

        searchFor("zzz-no-such-product-" + UUID.randomUUID());

        FxTestSupport.await("the empty message", () -> ui.shownInContent("productsEmpty"));
        assertThat(ui.text(ui.inContentRequired("productsEmpty"))).isEqualTo("Không tìm thấy sản phẩm phù hợp.");
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ add (AC-006-18 to -21, -27)

    @Test
    void addProduct_withAnImage_savesIt_toastsIt_andOpensTheEditScreenOfTheNewProduct() throws Exception {
        loginAndOpenProducts();
        openNewProductForm();
        String name = newName("Flask");
        fillNewProduct(name, "1.250.000", "30");
        smoke.typeInContent("productDescriptionField", "Giữ nhiệt 12 giờ");
        Path image = realPng("ảnh sản phẩm (1).PNG");
        chooseImage(image);
        smoke.awaitText("the chosen file name", "selectedImageName", "ảnh sản phẩm (1).PNG");
        smoke.awaitShown("imagePreview");
        smoke.awaitShown("clearImage");
        assertThat(chooser.calls()).isEqualTo(1);
        assertThat(ui.text(ui.inContentRequired("productImageError"))).as("no image error").isEmpty();
        ui.dismissToasts();

        smoke.fireInContent("saveProduct");

        smoke.awaitToast("Đã thêm sản phẩm " + name + ".");
        smoke.awaitText("the edit form title", "productFormTitle", "Sửa sản phẩm");
        smoke.awaitText("the name on the edit form", "productNameField", name);
        FxTestSupport.await("the edit form to load", () -> ui.shownInContent("stockCurrent"));
        assertThat(smoke.text("stockCurrent")).isEqualTo("Tồn kho hiện tại: 30");
        assertThat(smoke.text("productPriceField")).isNotEmpty();
        assertThat(smoke.text("productDescriptionField")).isEqualTo("Giữ nhiệt 12 giờ");
        assertThat(ui.shownInContent("currentImage")).as("the saved image is shown").isTrue();
        assertThat(ui.shownInContent("productInitialStockField")).as("no initial stock field when editing").isFalse();

        Map<String, Object> row = jdbc.queryForMap("select * from products where name = ?", name);
        assertThat(((BigDecimal) row.get("price")).longValueExact()).isEqualTo(1_250_000L);
        assertThat(row.get("stock")).isEqualTo(30);
        assertThat(row.get("active")).isEqualTo(true);
        String imagePath = (String) row.get("image_path");
        assertThat(imagePath).matches("/uploads/[0-9a-f-]{36}\\.png");
        assertThat(data.uploadFile(imagePath)).as("the file was stored").exists();
        assertThat(imagePath).as("the stored name is a UUID, not the picked name").doesNotContain("ảnh");
        assertThat(jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, name))
                .isEqualTo(1);
        ui.assertNoErrorDialog();
    }

    @Test
    void addProduct_withNoImage_savesWithoutAnImagePath() {
        loginAndOpenProducts();
        openNewProductForm();
        String name = newName("Plain");
        fillNewProduct(name, "99000", "4");
        ui.dismissToasts();

        smoke.fireInContent("saveProduct");

        smoke.awaitToast("Đã thêm sản phẩm " + name + ".");
        smoke.awaitText("the edit form title", "productFormTitle", "Sửa sản phẩm");
        assertThat(jdbc.queryForObject("select image_path from products where name = ?", String.class, name)).isNull();
        assertThat(chooser.calls()).isZero();
        ui.assertNoErrorDialog();
    }

    @Test
    void addProduct_withEmptyFields_showsAnErrorUnderEachFieldAndWritesNothing() {
        loginAndOpenProducts();
        openNewProductForm();
        int before = jdbc.queryForObject("select count(*) from products", Integer.class);

        smoke.fireInContent("saveProduct");

        smoke.awaitText("the name error", "productNameError", "Vui lòng nhập tên sản phẩm.");
        assertThat(smoke.text("productCategoryError")).isEqualTo("Vui lòng chọn danh mục.");
        assertThat(smoke.text("productPriceError")).isNotEmpty();
        assertThat(smoke.text("productInitialStockError")).isEqualTo("Vui lòng nhập tồn kho ban đầu.");
        assertThat(ui.inContent("productFormScreen")).as("stays on the form").isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isEqualTo(before);
        ui.assertNoErrorDialog();
    }

    @Test
    void addProduct_withABadPriceAndAnImageChosen_keepsTheImageAndTheTypedTextAndWritesNothing() throws Exception {
        loginAndOpenProducts();
        openNewProductForm();
        String name = newName("Badprice");
        fillNewProduct(name, "12,5", "3");
        chooseImage(realPng("keep-me.png"));
        smoke.awaitText("the chosen file name", "selectedImageName", "keep-me.png");
        int uploadsBefore = uploadFileCount();

        smoke.fireInContent("saveProduct");

        FxTestSupport.await("the price error", () -> !smoke.text("productPriceError").isEmpty());
        assertThat(smoke.text("productNameField")).isEqualTo(name);
        assertThat(smoke.text("selectedImageName")).as("the picked file is kept").isEqualTo("keep-me.png");
        assertThat(jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, name)).isZero();
        assertThat(uploadFileCount()).as("a form error never writes a file").isEqualTo(uploadsBefore);
        ui.assertNoErrorDialog();
    }

    @Test
    void chooseImage_withAGif_showsTheTypeErrorAndSelectsNothing() throws Exception {
        loginAndOpenProducts();
        openNewProductForm();

        chooseImage(fileWith("animated.gif", TestImages.gif()));

        smoke.awaitText("the image error", "productImageError", MSG_IMAGE_TYPE);
        assertThat(ui.shownInContent("imagePreview")).isFalse();
        assertThat(ui.shownInContent("clearImage")).isFalse();
        assertThat(smoke.text("selectedImageName")).isEmpty();
    }

    @Test
    void chooseImage_withHtmlRenamedToJpg_showsTheTypeError() throws Exception {
        loginAndOpenProducts();
        openNewProductForm();

        chooseImage(fileWith("fake.jpg", TestImages.htmlNamedJpg()));

        smoke.awaitText("the image error", "productImageError", MSG_IMAGE_TYPE);
        assertThat(ui.shownInContent("imagePreview")).isFalse();
    }

    @Test
    void chooseImage_withAFileOverTwoMegabytes_showsTheSizeError() throws Exception {
        loginAndOpenProducts();
        openNewProductForm();

        chooseImage(fileWith("huge.png", TestImages.png(2_500_000)));

        smoke.awaitText("the image error", "productImageError", MSG_IMAGE_SIZE);
        assertThat(ui.shownInContent("clearImage")).isFalse();
    }

    @Test
    void chooseImage_aBadFileAfterAGoodOne_keepsTheGoodSelection_andClearImageRemovesIt() throws Exception {
        loginAndOpenProducts();
        openNewProductForm();
        chooseImage(realPng("good.png"));
        smoke.awaitText("the chosen file name", "selectedImageName", "good.png");

        chooseImage(fileWith("bad.gif", TestImages.gif()));

        smoke.awaitText("the image error", "productImageError", MSG_IMAGE_TYPE);
        assertThat(smoke.text("selectedImageName")).as("the old choice stays").isEqualTo("good.png");
        assertThat(ui.shownInContent("imagePreview")).isTrue();

        smoke.fireInContent("clearImage");

        FxTestSupport.await("the choice to go", () -> smoke.text("selectedImageName").isEmpty());
        assertThat(ui.shownInContent("imagePreview")).isFalse();
        assertThat(ui.shownInContent("clearImage")).isFalse();
        assertThat(smoke.text("productImageError")).isEmpty();
    }

    @Test
    void chooseImage_whenTheUserCancelsTheChooser_changesNothing() {
        loginAndOpenProducts();
        openNewProductForm();
        chooser.willChoose(null);

        smoke.fireInContent("chooseImage");
        FxTestSupport.flushFx();

        assertThat(chooser.calls()).isEqualTo(1);
        assertThat(smoke.text("selectedImageName")).isEmpty();
        assertThat(smoke.text("productImageError")).isEmpty();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ edit (AC-006-22)

    @Test
    void editProduct_changesTheTextFieldsAndKeepsTheStockAndTheImage() {
        long id = data.createProduct(categoryOfTests(), "Edit me", 120_000, 9, true);
        data.setImagePath(id, "/images/products/ao-thun-cotton-co-tron.jpg");
        loginAndOpenProducts();
        openEditForm(id);
        smoke.awaitText("the name", "productNameField", data.productName(id));
        String renamed = newName("Renamed");
        smoke.typeInContent("productNameField", renamed);
        smoke.typeInContent("productPriceField", "135.000");
        ui.dismissToasts();

        smoke.fireInContent("saveProduct");

        smoke.awaitToast("Đã cập nhật sản phẩm " + renamed + ".");
        Map<String, Object> row = data.productRow(id);
        assertThat(row.get("name")).isEqualTo(renamed);
        assertThat(((BigDecimal) row.get("price")).longValueExact()).isEqualTo(135_000L);
        assertThat(row.get("stock")).as("saving never touches the stock").isEqualTo(9);
        assertThat(row.get("image_path")).isEqualTo("/images/products/ao-thun-cotton-co-tron.jpg");
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ hide and show (AC-006-25, -26)

    @Test
    void hideAndShow_fromTheList_askNothingToastAndMoveTheProductOutOfAndBackIntoTheShop() {
        long id = data.createProduct(categoryOfTests(), "Lamp", 250_000, 7, true);
        String name = data.productName(id);
        loginAndOpenProducts();
        searchFor(name);
        awaitProductRow(id);
        ui.dismissToasts();

        smoke.fireInContent("hideProduct-" + id);

        smoke.awaitToast("Đã ẩn sản phẩm " + name + ". Khách sẽ không thấy sản phẩm này nữa.");
        smoke.assertNoConfirm();
        FxTestSupport.await("the row to offer \"Hiện lại\"", () -> ui.inContent("showProduct-" + id) != null);
        assertThat(ui.labelsUnder(ui.inContentRequired("productRow-" + id))).contains("Đã ẩn");
        assertThat(jdbc.queryForObject("select active from products where id = ?", Boolean.class, id)).isFalse();
        awaitCatalogCard(id, false, name);

        // Back to the admin list and show it again
        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(new AdminProductsRoute(name, 1)));
        smoke.awaitScreen("adminProductsScreen");
        FxTestSupport.await("the row", () -> ui.inContent("showProduct-" + id) != null);
        ui.dismissToasts();
        smoke.fireInContent("showProduct-" + id);

        smoke.awaitToast("Đã hiện lại sản phẩm " + name + ".");
        smoke.assertNoConfirm();
        FxTestSupport.await("the row to offer \"Ẩn\"", () -> ui.inContent("hideProduct-" + id) != null);
        assertThat(jdbc.queryForObject("select active from products where id = ?", Boolean.class, id)).isTrue();
        awaitCatalogCard(id, true, name);
        ui.assertNoErrorDialog();
    }

    @Test
    void hideAndShow_fromTheEditScreen_swapTheButtonsAndTheFlag() {
        long id = data.createProduct(categoryOfTests(), "Chair", 500_000, 3, true);
        String name = data.productName(id);
        loginAndOpenProducts();
        openEditForm(id);
        smoke.awaitText("the flag", "productActiveFlag", "Đang bán");
        assertThat(ui.shownInContent("hideProductButton")).isTrue();
        assertThat(ui.shownInContent("showProductButton")).isFalse();
        ui.dismissToasts();

        smoke.fireInContent("hideProductButton");

        smoke.awaitToast("Đã ẩn sản phẩm " + name + ". Khách sẽ không thấy sản phẩm này nữa.");
        smoke.awaitText("the flag", "productActiveFlag", "Đã ẩn");
        assertThat(ui.shownInContent("showProductButton")).isTrue();
        assertThat(ui.shownInContent("hideProductButton")).isFalse();
        assertThat(ui.shownInContent("viewInStore")).as("a hidden product has no shop page").isFalse();
        assertThat(data.productRow(id).get("active")).isEqualTo(false);
        smoke.assertNoConfirm();
        ui.dismissToasts();

        smoke.fireInContent("showProductButton");

        smoke.awaitToast("Đã hiện lại sản phẩm " + name + ".");
        smoke.awaitText("the flag", "productActiveFlag", "Đang bán");
        assertThat(data.productRow(id).get("active")).isEqualTo(true);
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ stock (AC-006-32 to -34, -37)

    @Test
    void adjustStock_onAHiddenProduct_addsAndRemovesAndRefusesWhatWouldBreakTheLimits() {
        long id = data.createProduct(categoryOfTests(), "Stocked", 80_000, 10, false);
        String name = data.productName(id);
        loginAndOpenProducts();
        openEditForm(id);
        assertThat(smoke.text("stockCurrent")).isEqualTo("Tồn kho hiện tại: 10");
        ui.dismissToasts();

        // +5
        smoke.typeInContent("stockDeltaField", "5");
        smoke.fireInContent("adjustStock");
        smoke.awaitToast("Đã cập nhật tồn kho sản phẩm " + name + ". Tồn kho hiện tại: 15.");
        smoke.awaitText("the current stock", "stockCurrent", "Tồn kho hiện tại: 15");
        assertThat(data.stock(id)).isEqualTo(15);
        assertThat(smoke.text("stockDeltaField")).as("the field is cleared after success").isEmpty();

        // -40 is more than there is
        smoke.typeInContent("stockDeltaField", "-40");
        smoke.fireInContent("adjustStock");
        smoke.awaitText("the delta error", "stockDeltaError", "Không thể giảm 40 vì tồn kho hiện tại chỉ còn 15.");
        assertThat(data.stock(id)).isEqualTo(15);

        // Not a number, zero
        for (String bad : new String[] {"abc", "0", "", "1.5"}) {
            smoke.typeInContent("stockDeltaField", bad);
            smoke.fireInContent("adjustStock");
            smoke.awaitText("the delta error for \"" + bad + "\"", "stockDeltaError",
                    "Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.");
            assertThat(data.stock(id)).isEqualTo(15);
        }

        // More than the shelf limit
        smoke.typeInContent("stockDeltaField", "99990");
        smoke.fireInContent("adjustStock");
        smoke.awaitText("the delta error", "stockDeltaError", "Tồn kho sau điều chỉnh không được vượt quá 100.000.");
        assertThat(data.stock(id)).isEqualTo(15);

        // -5 works
        smoke.typeInContent("stockDeltaField", "-5");
        smoke.fireInContent("adjustStock");
        smoke.awaitText("the current stock", "stockCurrent", "Tồn kho hiện tại: 10");
        assertThat(data.stock(id)).isEqualTo(10);
        FxTestSupport.await("the error to clear", () -> smoke.text("stockDeltaError").isEmpty());
        assertThat(data.productRow(id).get("active")).as("adjusting stock does not show the product").isEqualTo(false);
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ layout

    @Test
    void editForm_inTwoColumnMode_keepsTheStockCardAtItsOwnHeight_notTheHeightOfTheFormCard() {
        long id = data.createProduct(categoryOfTests(), "Wide", 80_000, 10, true);
        loginAndOpenProducts();
        double originalWidth = FxTestSupport.callOnFx(mainStage::getWidth);
        FxTestSupport.runOnFx(() -> mainStage.setWidth(1400));
        try {
            FxTestSupport.await("the main window to be 1400 wide",
                    () -> FxTestSupport.callOnFx(() -> mainStage.getWidth() == 1400));
            openEditForm(id);
            javafx.scene.Node screen = smoke.node("productFormScreen");

            FxTestSupport.await("the two column layout", () -> FxTestSupport.callOnFx(() -> {
                screen.applyCss();
                javafx.scene.layout.GridPane columns = (javafx.scene.layout.GridPane) screen.lookup(".admin-columns");
                return columns != null && columns.getChildren().size() == 2 && columns.getWidth() > 0
                        && columns.getChildren().get(1).getLayoutX() > columns.getChildren().get(0).getLayoutX();
            }));
            FxTestSupport.flushFx();

            double[] heights = FxTestSupport.callOnFx(() -> {
                javafx.scene.layout.GridPane columns = (javafx.scene.layout.GridPane) screen.lookup(".admin-columns");
                javafx.scene.layout.Region formCard = (javafx.scene.layout.Region) columns.getChildren().get(0);
                javafx.scene.layout.Region stockCard = (javafx.scene.layout.Region) columns.getChildren().get(1);
                assertThat(stockCard.getStyleClass()).contains("admin-stock-card");
                return new double[] {stockCard.getHeight(), stockCard.prefHeight(stockCard.getWidth()),
                        formCard.getHeight(), stockCard.getLayoutY() - formCard.getLayoutY()};
            });
            assertThat(heights[0]).as("stock card height").isCloseTo(heights[1], org.assertj.core.data.Offset.offset(1.0));
            assertThat(heights[2]).as("the form card is the taller one, so the check means something")
                    .isGreaterThan(heights[0] + 50);
            assertThat(heights[3]).as("both cards start at the top").isCloseTo(0, org.assertj.core.data.Offset.offset(1.0));
            ui.assertNoErrorDialog();
        } finally {
            FxTestSupport.runOnFx(() -> mainStage.setWidth(originalWidth));
        }
    }

    // ------------------------------------------------------------------ not found (AC-009-13)

    @Test
    void editProduct_ofAProductThatDoesNotExist_toastsAndGoesBackToTheListWithoutAnErrorDialog() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        ui.dismissToasts();

        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(AdminProductFormRoute.edit(999_999,
                AdminProductsRoute.first())));

        smoke.awaitScreen("adminProductsScreen");
        smoke.awaitToast("Không tìm thấy sản phẩm.");
        assertThat(ui.inContent("productFormScreen")).isNull();
        ui.assertNoErrorDialog();
    }

    private static int uploadFileCount() {
        Path dir = Path.of("target", "test-uploads");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return (int) files.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
