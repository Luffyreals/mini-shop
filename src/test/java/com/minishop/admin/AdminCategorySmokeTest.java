package com.minishop.admin;

import com.minishop.desktop.ShellMenuEntry;
import com.minishop.support.AdminTestData;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testfx.api.FxToolkit;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The category screens on the real JavaFX toolkit (AC-006-10 to -16, AC-009-13; D5): the real app with an in-memory
 * database and the seed. The admin signs in through the real login dialog and the account menu; the screens are
 * driven by firing buttons and looking up nodes. No robot. Every category this class creates has a name that starts
 * with {@code Admin test } and is removed after each test, so the seed is left as found.
 */
@Tag("ui")
class AdminCategorySmokeTest {

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;
    private static AdminSmoke smoke;
    private static JdbcTemplate jdbc;
    private static AdminTestData data;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
        smoke = new AdminSmoke(ui);
        jdbc = new JdbcTemplate(ui.bean(DataSource.class));
        data = new AdminTestData(jdbc, null, Path.of("target", "test-uploads").toAbsolutePath().normalize());
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
        ui.resetToHome();
        data.cleanUp();
    }

    // ------------------------------------------------------------------ helpers

    private static String newName(String label) {
        return AdminTestData.NAME_PREFIX + label + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private static void openCategoryList() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("categories", "adminCategoriesScreen");
        FxTestSupport.await("the category list", () -> ui.shownInContent("categoryRows"));
    }

    private static long categoryId(String name) {
        return jdbc.queryForObject("select id from categories where name = ?", Long.class, name);
    }

    private static void awaitRow(long id) {
        FxTestSupport.await("the row of category " + id, () -> ui.inContent("categoryRow-" + id) != null);
    }

    private static void fillAndSave(String name, String description) {
        smoke.typeInContent("categoryNameField", name);
        smoke.typeInContent("categoryDescriptionField", description);
        smoke.fireInContent("saveCategory");
    }

    /** From the list: "Thêm danh mục", fill the form, save, and wait for the list with the new row. */
    private static long addCategory(String name, String description) {
        ui.dismissToasts();
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");
        fillAndSave(name, description);
        String clean = name.strip();
        smoke.awaitToast("Đã thêm danh mục " + clean + ".");
        smoke.awaitScreen("adminCategoriesScreen");
        long id = categoryId(clean);
        awaitRow(id);
        return id;
    }

    private static List<String> topBarCategoryIds() {
        return FxTestSupport.callOnFx(() -> ui.shell().categoriesMenuEntries().stream()
                .map(ShellMenuEntry::id).toList());
    }

    // ------------------------------------------------------------------ list

    @Test
    void categoryList_showsTheSeededCategoriesWithTheirProductCounts() {
        openCategoryList();

        assertThat(ui.text(ui.inContentRequired("categoriesTitle"))).isEqualTo("Danh mục");
        long homeId = jdbc.queryForObject("select id from categories where name = 'Nhà cửa'", Long.class);
        int homeProducts = jdbc.queryForObject("select count(*) from products where category_id = ?", Integer.class,
                homeId);
        awaitRow(homeId);
        assertThat(ui.labelsUnder(ui.inContentRequired("categoryRow-" + homeId)))
                .contains("Nhà cửa", Integer.toString(homeProducts), "Sửa", "Xóa");
        assertThat(ui.shownInContent("categoriesEmpty")).isFalse();
        ui.assertNoErrorDialog();
    }

    @Test
    void rowSpinner_readsDangTai_notDangXoa() {
        openCategoryList();
        long homeId = jdbc.queryForObject("select id from categories where name = 'Nhà cửa'", Long.class);
        awaitRow(homeId);

        javafx.scene.Node spinner = ui.inContentRequired("categoryBusy-" + homeId);

        assertThat(FxTestSupport.callOnFx(spinner::getAccessibleText)).isEqualTo("Đang tải");
        assertThat(ui.shownInContent("categoryBusy-" + homeId)).as("idle rows show no spinner").isFalse();
    }

    @Test
    void formSpinner_readsDangTai_notDangLuu() {
        openCategoryList();
        ui.dismissToasts();
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");

        javafx.scene.Node spinner = smoke.node("categoryFormBusy");

        assertThat(FxTestSupport.callOnFx(spinner::getAccessibleText)).isEqualTo("Đang tải");
        assertThat(ui.shownInContent("categoryFormBusy")).as("an idle form shows no spinner").isFalse();
    }

    // ------------------------------------------------------------------ add (AC-006-11 to -13)

    @Test
    void addCategory_withAName_listsItToastsItAndAddsItToTheTopBarMenuAtOnce() {
        openCategoryList();
        String name = newName("Toys");

        long id = addCategory("  " + name + "  ", "");

        assertThat(ui.labelsUnder(ui.inContentRequired("categoryRow-" + id))).contains(name, "0");
        assertThat(jdbc.queryForObject("select name from categories where id = ?", String.class, id)).isEqualTo(name);
        FxTestSupport.await("the top bar menu to list the new category",
                () -> topBarCategoryIds().contains("categoryMenu-" + id));
        ui.assertNoErrorDialog();
    }

    @Test
    void addCategory_withATakenNameInOtherCase_showsTheErrorUnderTheNameKeepsTheTextAndAddsNothing() {
        openCategoryList();
        String name = newName("Taken");
        addCategory(name, "");
        ui.dismissToasts();
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");

        String shouted = name.toUpperCase(Locale.ROOT);
        fillAndSave(shouted, "");

        smoke.awaitText("the name error", "categoryNameError", "Tên danh mục đã tồn tại.");
        assertThat(ui.inContent("categoryFormScreen")).as("stays on the form").isNotNull();
        assertThat(smoke.text("categoryNameField")).as("the typed text is kept").isEqualTo(shouted);
        assertThat(data.categoryCountIgnoringCase(name)).isEqualTo(1);
        assertThat(ui.toastTexts()).as("no success toast").doesNotContain("Đã thêm danh mục " + shouted + ".");
        ui.assertNoErrorDialog();
    }

    @Test
    void addCategory_withAnEmptyName_showsTheValidationMessageAndCallsNoService() {
        openCategoryList();
        int before = jdbc.queryForObject("select count(*) from categories", Integer.class);
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");

        fillAndSave("   ", "");

        smoke.awaitText("the name error", "categoryNameError", "Vui lòng nhập tên danh mục.");
        assertThat(ui.inContent("categoryFormScreen")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from categories", Integer.class)).isEqualTo(before);
        ui.assertNoErrorDialog();
    }

    @Test
    void addCategory_withATooLongNameAndDescription_showsBothErrorsAndKeepsTheText() {
        openCategoryList();
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");
        String longName = "a".repeat(101);
        String longDescription = "b".repeat(1001);

        fillAndSave(longName, longDescription);

        smoke.awaitText("the name error", "categoryNameError", "Tên danh mục tối đa 100 ký tự.");
        assertThat(smoke.text("categoryDescriptionError")).isEqualTo("Mô tả tối đa 1000 ký tự.");
        assertThat(smoke.text("categoryNameField")).isEqualTo(longName);
        assertThat(smoke.text("categoryDescriptionField")).isEqualTo(longDescription);
        assertThat(data.categoryCountByName(longName)).isZero();
    }

    @Test
    void cancelButton_onTheAddForm_goesBackToTheListWithoutSaving() {
        openCategoryList();
        String name = newName("Cancelled");
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");
        smoke.typeInContent("categoryNameField", name);

        smoke.fireInContent("cancelCategory");

        smoke.awaitScreen("adminCategoriesScreen");
        assertThat(data.categoryCountByName(name)).isZero();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ edit (AC-006-14)

    @Test
    void editCategory_prefillsTheFormAndSavingRenamesIt() {
        openCategoryList();
        String name = newName("Before");
        long id = addCategory(name, "old description");
        ui.dismissToasts();

        smoke.fireInContent("editCategory-" + id);

        smoke.awaitScreen("categoryFormScreen");
        smoke.awaitText("the prefilled name", "categoryNameField", name);
        assertThat(smoke.text("categoryDescriptionField")).isEqualTo("old description");
        String renamed = newName("After");
        fillAndSave(renamed, "");

        smoke.awaitToast("Đã cập nhật danh mục " + renamed + ".");
        smoke.awaitScreen("adminCategoriesScreen");
        awaitRow(id);
        FxTestSupport.await("the renamed row", () -> ui.labelsUnder(ui.inContentRequired("categoryRow-" + id))
                .contains(renamed));
        assertThat(data.categoryName(id)).isEqualTo(renamed);
        ui.assertNoErrorDialog();
    }

    @Test
    void editCategory_ofACategoryThatNoLongerExists_toastsAndGoesBackToTheList() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        ui.dismissToasts();

        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(AdminCategoryFormRoute.edit(999_999)));

        smoke.awaitScreen("adminCategoriesScreen");
        smoke.awaitToast("Không tìm thấy danh mục.");
        assertThat(ui.inContent("categoryFormScreen")).isNull();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ delete (AC-006-15, -16)

    @Test
    void deleteCategory_asksToConfirm_keepsItOnDismiss_andOnConfirmDeletesItAndRemovesItFromTheTopBarMenu() {
        openCategoryList();
        String name = newName("Doomed");
        long id = addCategory(name, "");
        FxTestSupport.await("the top bar menu to list it", () -> topBarCategoryIds().contains("categoryMenu-" + id));
        ui.dismissToasts();

        // Keep it: nothing changes
        smoke.fireInContent("deleteCategory-" + id);
        Window dialog = smoke.awaitConfirm("Xóa danh mục " + name + "? Không thể hoàn tác.");
        assertThat(ui.text(ui.inWindow(dialog, "confirmAccept"))).isEqualTo("Xóa");
        assertThat(ui.text(ui.inWindow(dialog, "confirmDismiss"))).isEqualTo("Quay lại");
        smoke.dismissConfirm(dialog);
        assertThat(data.categoryExists(id)).as("dismissing deletes nothing").isTrue();
        assertThat(ui.inContent("categoryRow-" + id)).isNotNull();

        // Confirm: it goes
        smoke.fireInContent("deleteCategory-" + id);
        smoke.acceptConfirm(smoke.awaitConfirm("Xóa danh mục " + name + "? Không thể hoàn tác."));

        smoke.awaitToast("Đã xóa danh mục " + name + ".");
        FxTestSupport.await("the row to go", () -> ui.inContent("categoryRow-" + id) == null);
        assertThat(data.categoryExists(id)).isFalse();
        FxTestSupport.await("the top bar menu to drop it", () -> !topBarCategoryIds().contains("categoryMenu-" + id));
        ui.assertNoErrorDialog();
    }

    @Test
    void deleteCategory_withProducts_isRefusedWithTheCountOfAllProductsIncludingHiddenOnes() {
        long categoryId = data.createCategory("InUse");
        data.createProduct(categoryId, "visible", 100_000, 5, true);
        data.createProduct(categoryId, "hidden", 100_000, 5, false);
        String name = data.categoryName(categoryId);
        openCategoryList();
        awaitRow(categoryId);
        ui.dismissToasts();

        smoke.fireInContent("deleteCategory-" + categoryId);
        smoke.acceptConfirm(smoke.awaitConfirm("Xóa danh mục " + name + "? Không thể hoàn tác."));

        smoke.awaitToast("Không thể xóa danh mục " + name + " vì còn 2 sản phẩm (kể cả sản phẩm đã ẩn).");
        assertThat(data.categoryExists(categoryId)).isTrue();
        assertThat(data.productCount(categoryId)).isEqualTo(2);
        assertThat(ui.inContent("categoryRow-" + categoryId)).as("the row stays").isNotNull();
        assertThat(data.orphanedProductCount()).isZero();
        ui.assertNoErrorDialog();
    }
}
