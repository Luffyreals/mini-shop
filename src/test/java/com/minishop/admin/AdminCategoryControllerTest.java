package com.minishop.admin;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDeleteResult;
import com.minishop.catalog.CategoryDraft;
import com.minishop.catalog.CategoryEditView;
import com.minishop.catalog.CategoryNameTakenException;
import com.minishop.catalog.CategoryRow;
import com.minishop.common.NotFoundException;
import com.minishop.config.SecurityConfig;
import com.minishop.user.Role;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.regex.Pattern;

import static com.minishop.admin.AdminPageAssertions.assertLabelled;
import static com.minishop.admin.AdminPageAssertions.assertLayout;
import static com.minishop.admin.AdminPageAssertions.formWithAction;
import static com.minishop.admin.AdminPageAssertions.main;
import static com.minishop.admin.AdminPageAssertions.matches;
import static com.minishop.admin.AdminPageAssertions.tagWithId;
import static com.minishop.admin.AdminPageAssertions.unescape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Category pages with a mocked {@code CategoryAdminService} (AC-006-4, 5, 10, 11, 12, 13, 14, 15, 16, 49): list,
 * forms with their errors, PRG redirects with the exact flash texts, and the admin 404.
 */
@WebMvcTest(AdminCategoryController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AdminCategoryControllerTest {

    private static final String EVIL = "<script>alert(1)</script>";
    private static final String EVIL_ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;";
    private static final String NOT_FOUND_TITLE = "Không tìm thấy danh mục";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CategoryAdminService categoryService;

    private static RequestPostProcessor admin() {
        return user(TestUsers.admin());
    }

    private String html(String url) throws Exception {
        return mockMvc.perform(get(url).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------ AC-006-10: list

    @Test
    void list_rendersLayoutColumnsCountsAndActions() throws Exception {
        when(categoryService.list()).thenReturn(List.of(
                new CategoryRow(3, "Đồ chơi", "Đồ chơi trẻ em", 2), new CategoryRow(4, "Sách", null, 0)));

        String html = mockMvc.perform(get("/admin/categories").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-list"))
                .andExpect(model().attributeExists("categories"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Danh mục | Quản trị Mini Shop", "Danh mục", "categories");
        for (String column : List.of("Tên", "Mô tả", "Số sản phẩm")) {
            assertThat(matches("<th scope=\"col\"[^>]*>\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        assertThat(html).contains("Đồ chơi trẻ em");
        assertThat(matches("<td[^>]*>\\s*Đồ chơi\\s*</td>\\s*<td[^>]*>\\s*Đồ chơi trẻ em\\s*</td>\\s*<td[^>]*>\\s*2\\s*</td>",
                html)).as("name, description, count 2 of a category with a hidden product").isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/categories/new\"[^>]*>\\s*Thêm danh mục\\s*</a>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/categories/3/edit\"[^>]*>\\s*Sửa\\s*</a>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/categories/4/edit\"[^>]*>\\s*Sửa\\s*</a>", html)).isTrue();
        assertThat(html).doesNotContain("Chưa có danh mục nào.");
    }

    @Test
    void list_keepsTheOrderOfTheService() throws Exception {
        when(categoryService.list()).thenReturn(List.of(new CategoryRow(1, "Áo", null, 0),
                new CategoryRow(2, "Bàn", null, 0), new CategoryRow(3, "Đèn", null, 0)));

        String html = html("/admin/categories");

        assertThat(html.indexOf("Áo")).isLessThan(html.indexOf("Bàn"));
        assertThat(html.indexOf("Bàn")).isLessThan(html.indexOf("Đèn"));
    }

    @Test
    void list_deleteFormHasCsrfPostAndExactConfirmText_andConfirmScriptIsLoaded() throws Exception {
        when(categoryService.list()).thenReturn(List.of(new CategoryRow(3, "Đồ chơi", null, 0)));

        String html = html("/admin/categories");

        String form = formWithAction(html, "/admin/categories/3/delete");
        assertThat(form).contains("method=\"post\"").contains("name=\"_csrf\"")
                .contains("data-confirm=\"Xóa danh mục Đồ chơi? Không thể hoàn tác.\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Xóa\\s*</button>", form)).isTrue();
        assertThat(html).contains("<script src=\"/js/confirm.js\" defer></script>");
    }

    @Test
    void list_withoutCategories_showsEmptyMessageAndNoTable() throws Exception {
        when(categoryService.list()).thenReturn(List.of());

        String html = html("/admin/categories");

        assertThat(html).contains("Chưa có danh mục nào.");
        assertThat(main(html)).doesNotContain("<table");
    }

    @Test
    void list_nameAndDescriptionWithScript_areEscapedAlsoInConfirmAttribute() throws Exception {
        when(categoryService.list()).thenReturn(List.of(new CategoryRow(3, EVIL, EVIL, 0)));

        String html = html("/admin/categories");

        assertThat(html).doesNotContain(EVIL);
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).isGreaterThanOrEqualTo(3);
    }

    @Test
    void list_withFlashNotice_showsItWithRoleForItsKind() throws Exception {
        when(categoryService.list()).thenReturn(List.of());

        String ok = mockMvc.perform(get("/admin/categories").with(admin())
                        .flashAttr("notice", "Đã thêm danh mục Đồ chơi.").flashAttr("noticeKind", "success"))
                .andReturn().getResponse().getContentAsString();
        String error = mockMvc.perform(get("/admin/categories").with(admin())
                        .flashAttr("notice", "Không thể xóa").flashAttr("noticeKind", "error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(ok).contains("Đã thêm danh mục Đồ chơi.").contains("role=\"status\"");
        assertThat(error).contains("Không thể xóa").contains("role=\"alert\"");
    }

    @Test
    void list_adminNameWithScript_isEscaped() throws Exception {
        when(categoryService.list()).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/categories").with(user(TestUsers.withName(EVIL, Role.ADMIN))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Xin chào, " + EVIL_ESCAPED).doesNotContain(EVIL);
    }

    // ------------------------------------------------------------ new form

    @Test
    void newForm_rendersLabelsLimitsAndButtons() throws Exception {
        String html = mockMvc.perform(get("/admin/categories/new").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-form"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Thêm danh mục | Quản trị Mini Shop", "Thêm danh mục", "categories");
        String form = formWithAction(html, "/admin/categories");
        assertThat(form).contains("method=\"post\"").contains("novalidate").contains("name=\"_csrf\"");
        assertLabelled(html, "name");
        assertLabelled(html, "description");
        assertThat(matches("<label[^>]*for=\"name\"[^>]*>\\s*Tên danh mục\\s*</label>", form)).isTrue();
        assertThat(matches("<label[^>]*for=\"description\"[^>]*>\\s*Mô tả \\(không bắt buộc\\)\\s*</label>", form)).isTrue();
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Lưu\\s*</button>", form)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/categories\"[^>]*>\\s*Hủy\\s*</a>", form)).isTrue();
        assertThat(tagWithId(form, "name")).contains("maxlength=\"100\"");
        assertThat(tagWithId(form, "description")).contains("maxlength=\"1000\"");
        verifyNoInteractions(categoryService);
    }

    // ------------------------------------------------------------ AC-006-11: create

    @Test
    void create_withPaddedNameAndBlankDescription_passesCleanDraftAndRedirectsWithFlash() throws Exception {
        when(categoryService.create(eq(1L), any(CategoryDraft.class))).thenReturn("Đồ chơi");

        mockMvc.perform(post("/admin/categories").with(admin()).with(csrf())
                        .param("name", "  Đồ chơi  ").param("description", "   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/categories"))
                .andExpect(flash().attribute("notice", "Đã thêm danh mục Đồ chơi."))
                .andExpect(flash().attribute("noticeKind", "success"));

        verify(categoryService).create(1L, new CategoryDraft("Đồ chơi", null));
    }

    @Test
    void create_withDescription_passesItAndIgnoresReferer() throws Exception {
        when(categoryService.create(eq(1L), any(CategoryDraft.class))).thenReturn("Sách");

        mockMvc.perform(post("/admin/categories").with(admin()).with(csrf())
                        .param("name", "Sách").param("description", "  Sách và văn phòng phẩm \n")
                        .header("Referer", "https://evil.example/"))
                .andExpect(redirectedUrl("/admin/categories"));

        verify(categoryService).create(1L, new CategoryDraft("Sách", "Sách và văn phòng phẩm"));
    }

    // ------------------------------------------------------------ AC-006-12: validation

    @ParameterizedTest(name = "[{index}] invalid name -> {1}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "''|Vui lòng nhập tên danh mục.",
            "'    '|Vui lòng nhập tên danh mục."})
    void create_withBlankName_rendersFormWithOneErrorUnderNameAndDoesNotCallService(String name, String message)
            throws Exception {
        String html = mockMvc.perform(post("/admin/categories").with(admin()).with(csrf())
                        .param("name", name).param("description", "Mô tả đã nhập"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(matches("<p[^>]*class=\"field-error\"[^>]*id=\"name-error\"[^>]*>\\s*" + Pattern.quote(message)
                + "\\s*</p>", html) || matches("<p[^>]*id=\"name-error\"[^>]*class=\"field-error\"[^>]*>\\s*"
                + Pattern.quote(message) + "\\s*</p>", html)).isTrue();
        assertThat(html).containsOnlyOnce(message);
        assertThat(tagWithId(html, "name")).contains("aria-invalid=\"true\"").contains("aria-describedby=\"name-error\"");
        assertThat(unescape(html)).contains("Mô tả đã nhập");
        verifyNoInteractions(categoryService);
    }

    @Test
    void create_withNameOf101Characters_showsLengthErrorAndKeepsTypedValues() throws Exception {
        String longName = "Đ".repeat(101);

        String html = mockMvc.perform(post("/admin/categories").with(admin()).with(csrf())
                        .param("name", longName).param("description", "Giữ lại mô tả"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Tên danh mục tối đa 100 ký tự.").contains("value=\"" + longName + "\"")
                .contains("Giữ lại mô tả");
        verifyNoInteractions(categoryService);
    }

    @Test
    void create_withDescriptionOf1001Characters_showsErrorUnderDescriptionAndKeepsName() throws Exception {
        String html = mockMvc.perform(post("/admin/categories").with(admin()).with(csrf())
                        .param("name", "Đồ chơi").param("description", "m".repeat(1001)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Mô tả tối đa 1000 ký tự.").contains("value=\"Đồ chơi\"");
        assertThat(tagWithId(html, "description")).contains("aria-invalid=\"true\"")
                .contains("aria-describedby=\"description-error\"");
        assertThat(html).contains("id=\"description-error\"");
        assertThat(tagWithId(html, "name")).doesNotContain("aria-invalid");
        verifyNoInteractions(categoryService);
    }

    @Test
    void create_whenNameIsTaken_rendersFormWithErrorUnderNameAndKeepsValues() throws Exception {
        when(categoryService.create(eq(1L), any(CategoryDraft.class))).thenThrow(new CategoryNameTakenException("taken"));

        String html = mockMvc.perform(post("/admin/categories").with(admin()).with(csrf())
                        .param("name", " ĐỒ CHƠI ").param("description", "Mô tả khác"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Tên danh mục đã tồn tại.").contains("value=\"ĐỒ CHƠI\"").contains(">Mô tả khác<");
        assertThat(tagWithId(html, "name")).contains("aria-invalid=\"true\"").contains("aria-describedby=\"name-error\"");
        assertThat(formWithAction(html, "/admin/categories")).contains("name=\"_csrf\"");
    }

    // ------------------------------------------------------------ AC-006-14: edit

    @Test
    void editForm_prefillsFormAndPostsToTheCategoryUrl() throws Exception {
        when(categoryService.get(7L)).thenReturn(new CategoryEditView(7, "Đồ chơi", "Đồ chơi trẻ em"));

        String html = mockMvc.perform(get("/admin/categories/7/edit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-form"))
                .andExpect(model().attribute("mode", "edit"))
                .andExpect(model().attribute("categoryId", 7L))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Sửa danh mục | Quản trị Mini Shop", "Sửa danh mục", "categories");
        String form = formWithAction(html, "/admin/categories/7");
        assertThat(unescape(form)).contains("value=\"Đồ chơi\"").contains(">Đồ chơi trẻ em<").contains("name=\"_csrf\"");
        assertThat(tagWithId(form, "name")).contains("maxlength=\"100\"");
        assertThat(tagWithId(form, "description")).contains("maxlength=\"1000\"");
    }

    @Test
    void update_withValidForm_redirectsToListWithFlash() throws Exception {
        when(categoryService.update(eq(1L), eq(7L), any(CategoryDraft.class))).thenReturn("Đồ chơi trẻ em");

        mockMvc.perform(post("/admin/categories/7").with(admin()).with(csrf())
                        .param("name", "Đồ chơi trẻ em").param("description", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/categories"))
                .andExpect(flash().attribute("notice", "Đã cập nhật danh mục Đồ chơi trẻ em."))
                .andExpect(flash().attribute("noticeKind", "success"));

        verify(categoryService).update(1L, 7L, new CategoryDraft("Đồ chơi trẻ em", null));
    }

    @Test
    void update_withInvalidName_rendersEditFormAndDoesNotUpdate() throws Exception {
        when(categoryService.get(7L)).thenReturn(new CategoryEditView(7, "Đồ chơi", null));

        String html = mockMvc.perform(post("/admin/categories/7").with(admin()).with(csrf())
                        .param("name", "").param("description", "x"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Vui lòng nhập tên danh mục.");
        assertThat(formWithAction(html, "/admin/categories/7")).contains("name=\"_csrf\"");
        verify(categoryService, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    void update_whenNameBelongsToAnotherCategory_rendersErrorUnderName() throws Exception {
        when(categoryService.update(eq(1L), eq(7L), any(CategoryDraft.class)))
                .thenThrow(new CategoryNameTakenException("taken"));

        String html = mockMvc.perform(post("/admin/categories/7").with(admin()).with(csrf())
                        .param("name", "Đồ Chơi").param("description", ""))
                .andExpect(status().isOk())
                .andExpect(model().attribute("mode", "edit"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Tên danh mục đã tồn tại.");
        assertThat(formWithAction(html, "/admin/categories/7")).contains("value=\"Đồ Chơi\"");
    }

    // ------------------------------------------------------------ AC-006-15, 16: delete

    @Test
    void delete_whenDeleted_redirectsToListWithSuccessFlash() throws Exception {
        when(categoryService.delete(1L, 3L)).thenReturn(new CategoryDeleteResult.Deleted("Đồ chơi"));

        mockMvc.perform(post("/admin/categories/3/delete").with(admin()).with(csrf())
                        .header("Referer", "https://evil.example/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/categories"))
                .andExpect(flash().attribute("notice", "Đã xóa danh mục Đồ chơi."))
                .andExpect(flash().attribute("noticeKind", "success"));
    }

    @Test
    void delete_whenInUse_redirectsToListWithErrorFlashNamingTheCount() throws Exception {
        when(categoryService.delete(1L, 3L)).thenReturn(new CategoryDeleteResult.InUse("Đồ chơi", 2));

        mockMvc.perform(post("/admin/categories/3/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/categories"))
                .andExpect(flash().attribute("notice",
                        "Không thể xóa danh mục Đồ chơi vì còn 2 sản phẩm (kể cả sản phẩm đã ẩn)."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    // ------------------------------------------------------------ AC-006-5: 404

    @ParameterizedTest(name = "GET {0} -> admin 404 without calling the service")
    @ValueSource(strings = {"/admin/categories/x/edit", "/admin/categories/-1/edit",
            "/admin/categories/99999999999999999999/edit", "/admin/categories/1.5/edit"})
    void editForm_withBadId_is404InAdminLayout(String url) throws Exception {
        String html = mockMvc.perform(get(url).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString();

        assertNotFoundPage(html);
        verifyNoInteractions(categoryService);
    }

    @Test
    void editForm_whenServiceThrowsNotFound_is404InAdminLayout() throws Exception {
        when(categoryService.get(999L)).thenThrow(new NotFoundException("Category not found: 999"));

        String html = mockMvc.perform(get("/admin/categories/999/edit").with(admin()))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString();

        assertNotFoundPage(html);
    }

    @Test
    void postsToBadOrUnknownId_are404AndChangeNothing() throws Exception {
        when(categoryService.delete(1L, 999L)).thenThrow(new NotFoundException("Category not found: 999"));
        when(categoryService.update(eq(1L), eq(999L), any(CategoryDraft.class)))
                .thenThrow(new NotFoundException("Category not found: 999"));

        for (String url : List.of("/admin/categories/x/delete", "/admin/categories/999/delete")) {
            assertNotFoundPage(mockMvc.perform(post(url).with(admin()).with(csrf()))
                    .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                    .andReturn().getResponse().getContentAsString());
        }
        for (String url : List.of("/admin/categories/x", "/admin/categories/999")) {
            assertNotFoundPage(mockMvc.perform(post(url).with(admin()).with(csrf()).param("name", "Tên"))
                    .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                    .andReturn().getResponse().getContentAsString());
        }
        verify(categoryService, never()).update(eq(1L), eq(0L), any());
    }

    private static void assertNotFoundPage(String html) {
        assertLayout(html, NOT_FOUND_TITLE + " | Quản trị Mini Shop", NOT_FOUND_TITLE, "categories");
        assertThat(matches("<a[^>]*href=\"/admin/categories\"[^>]*>\\s*Quay lại danh sách danh mục\\s*</a>", main(html)))
                .isTrue();
    }

    // ------------------------------------------------------------ AC-006-49: labels

    @Test
    void forms_newAndEdit_everyControlHasALabel() throws Exception {
        when(categoryService.get(7L)).thenReturn(new CategoryEditView(7, "Đồ chơi", null));

        for (String url : List.of("/admin/categories/new", "/admin/categories/7/edit")) {
            String html = html(url);
            assertLabelled(html, "name");
            assertLabelled(html, "description");
        }
    }
}
