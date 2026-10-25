package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.support.AdminTestData;
import com.minishop.support.AdminTestData.Outcome;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category administration on a real PostgreSQL (AC-006-11, 13, 16, 51): create through MockMvc, the
 * case-insensitive duplicate rule, the two races of QĐ4 (same name twice; delete against add-product) and the
 * category-with-count query. No @Transactional (races need real transactions); only {@link AdminTestData} rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryAdminIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    CategoryAdminService categoryAdminService;

    @Autowired
    ProductAdminService productAdminService;

    @Autowired
    CategoryRepository categoryRepository;

    @Value("${app.storage.local.upload-dir}")
    String uploadDir;

    AdminTestData data;
    AdminTestData.TestUser admin;

    @BeforeEach
    void setUp() {
        data = new AdminTestData(jdbc, encoder, Path.of(uploadDir).toAbsolutePath().normalize());
        data.cleanUp();
        admin = data.createUser(Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private RequestPostProcessor loggedInAdmin() {
        return user(new CurrentUser(admin.id(), admin.email(), null, admin.fullName(), Role.ADMIN));
    }

    private static String unique(String base) {
        return AdminTestData.NAME_PREFIX + base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ProductDraft productDraft(long categoryId) {
        return new ProductDraft(unique("race product"), categoryId, new BigDecimal("100000"), null);
    }

    // ------------------------------------------------------ AC-006-11, 51

    @Test
    void postCategory_withPaddedNameAndBlankDescription_savesTrimmedNameNullDescriptionAndShowsInStorefrontFilter()
            throws Exception {
        String name = unique("Đồ chơi");

        mockMvc.perform(post("/admin/categories").with(loggedInAdmin()).with(csrf())
                        .param("name", "  " + name + "  ").param("description", "   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/categories"))
                .andExpect(flash().attribute("notice", "Đã thêm danh mục " + name + "."))
                .andExpect(flash().attribute("noticeKind", "success"));

        assertThat(data.categoryCountByName(name)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select description from categories where name = ?", String.class, name))
                .isNull();
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)));
    }

    // ---------------------------------------------------------- AC-006-13

    @Test
    void create_withSameNameInOtherCase_isRejectedAndNothingIsSaved() {
        String name = unique("Đồ chơi");
        categoryAdminService.create(admin.id(), new CategoryDraft(name, null));

        for (String variant : List.of(name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT))) {
            assertThatThrownBy(
                            () -> categoryAdminService.create(admin.id(), new CategoryDraft(variant, null)))
                    .isInstanceOf(CategoryNameTakenException.class);
        }
        assertThat(data.categoryCountIgnoringCase(name)).isEqualTo(1);
    }

    @Test
    void race_twoCreatesOfSameName_exactlyOneRowAndOtherGetsNameTaken() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            String name = unique("Race name");

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> categoryAdminService.create(admin.id(), new CategoryDraft(name, null)),
                    () -> categoryAdminService.create(admin.id(), new CategoryDraft(name, null)));

            assertThat(results).as("round %d", round).filteredOn(r -> !r.failed()).hasSize(1);
            assertThat(results).filteredOn(Outcome::failed).singleElement()
                    .satisfies(r -> assertThat(r.error()).isInstanceOf(CategoryNameTakenException.class));
            assertThat(data.categoryCountByName(name)).isEqualTo(1);
        }
        System.out.printf("AC-006-13 same-name race, %d rounds: %d ms%n", ROUNDS, (System.nanoTime() - start) / 1_000_000);
    }

    // ---------------------------------------------------------- AC-006-16

    @Test
    void delete_categoryWithOneActiveAndOneHiddenProduct_isRefusedWithCountTwo() {
        long category = data.createCategory("In use");
        data.createProduct(category, "active", 100_000, 5, true);
        data.createProduct(category, "hidden", 100_000, 5, false);
        String name = data.categoryName(category);

        CategoryDeleteResult result = categoryAdminService.delete(admin.id(), category);

        assertThat(result).isEqualTo(new CategoryDeleteResult.InUse(name, 2));
        assertThat(data.categoryExists(category)).isTrue();
        assertThat(data.productCount(category)).isEqualTo(2);
    }

    @Test
    void delete_emptyCategory_removesTheRow() {
        long category = data.createCategory("Empty");
        String name = data.categoryName(category);

        CategoryDeleteResult result = categoryAdminService.delete(admin.id(), category);

        assertThat(result).isEqualTo(new CategoryDeleteResult.Deleted(name));
        assertThat(data.categoryExists(category)).isFalse();
    }

    /**
     * Delete takes the {@code categories} row lock and then counts; add-product takes the same lock first
     * (ADR-0010). Whoever is second sees the other's committed result, so never a foreign-key error, a 500 or
     * a product pointing at a deleted category.
     */
    @Test
    void race_deleteCategoryAgainstAddProduct_neverBothSucceedAndNeverLeavesOrphans() throws Exception {
        long start = System.nanoTime();
        int deletedRounds = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long category = data.createCategory("Delete vs add");

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> categoryAdminService.delete(admin.id(), category),
                    () -> productAdminService.create(admin.id(), productDraft(category), 5, true, null));

            Outcome<Object> delete = results.get(0);
            Outcome<Object> create = results.get(1);
            assertThat(delete.failed()).as("round %d: delete must not throw: %s", round, delete.error()).isFalse();
            if (delete.value() instanceof CategoryDeleteResult.Deleted) {
                assertThat(create.error()).as("round %d", round).isInstanceOf(CategoryNotFoundException.class);
                assertThat(data.categoryExists(category)).isFalse();
                deletedRounds++;
            } else {
                assertThat(delete.value()).as("round %d", round).isInstanceOf(CategoryDeleteResult.InUse.class);
                assertThat(((CategoryDeleteResult.InUse) delete.value()).productCount()).isEqualTo(1);
                assertThat(create.failed()).as("round %d: %s", round, create.error()).isFalse();
                assertThat(data.categoryExists(category)).isTrue();
                assertThat(data.productCount(category)).isEqualTo(1);
            }
            assertThat(data.orphanedProductCount()).as("round %d", round).isZero();
        }
        System.out.printf("AC-006-16 delete-vs-add race, %d rounds (%d deleted first): %d ms%n", ROUNDS, deletedRounds,
                (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Update locks the category row like delete does (ADR-0010), so one of them is simply second: either the
     * rename commits and the delete then removes the (still empty) category, or the delete commits first and the
     * update reports not-found. Never a 500 or an untranslated persistence exception.
     */
    @Test
    void race_updateCategoryAgainstDeleteOfTheSameEmptyCategory_endsInEitherOrderWithoutRawErrors() throws Exception {
        int renamedFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long category = data.createCategory("Update vs delete");
            String newName = unique("Renamed");

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> categoryAdminService.update(admin.id(), category, new CategoryDraft(newName, "mới")),
                    () -> categoryAdminService.delete(admin.id(), category));

            Outcome<Object> update = results.get(0);
            Outcome<Object> delete = results.get(1);
            assertThat(delete.failed()).as("round %d: delete must not throw: %s", round, delete.error()).isFalse();
            assertThat(delete.value()).as("round %d", round).isInstanceOf(CategoryDeleteResult.Deleted.class);
            if (update.failed()) {
                assertThat(update.error()).as("round %d", round).isInstanceOf(NotFoundException.class);
                assertThat(((CategoryDeleteResult.Deleted) delete.value()).name()).startsWith(AdminTestData.NAME_PREFIX);
            } else {
                assertThat(update.value()).isEqualTo(newName);
                renamedFirst++;
            }
            assertThat(data.categoryExists(category)).as("round %d", round).isFalse();
        }
        System.out.printf("update-vs-delete race, %d rounds (%d renamed first)%n", ROUNDS, renamedFirst);
    }

    // ------------------------------------------------- runtime of the query

    /** schema-guardian: the {@code select new CategoryRow} with a left join runs and counts hidden products. */
    @Test
    void findAllWithProductCount_countsHiddenProductsAndGivesZeroForEmptyCategories() {
        long empty = data.createCategory("Count empty");
        long full = data.createCategory("Count full");
        data.createProduct(full, "a", 100_000, 5, true);
        data.createProduct(full, "b", 100_000, 5, false);

        List<CategoryRow> rows = categoryRepository.findAllWithProductCount();

        assertThat(rows).filteredOn(r -> r.id() == empty).singleElement()
                .satisfies(r -> assertThat(r.productCount()).isZero());
        assertThat(rows).filteredOn(r -> r.id() == full).singleElement()
                .satisfies(r -> assertThat(r.productCount()).isEqualTo(2));
        assertThat(rows).extracting(CategoryRow::name).contains(data.categoryName(empty), data.categoryName(full));
    }
}
