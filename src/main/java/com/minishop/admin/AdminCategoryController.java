package com.minishop.admin;

import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDeleteResult;
import com.minishop.catalog.CategoryNameTakenException;
import com.minishop.common.NotFoundException;
import com.minishop.user.CurrentUser;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin category pages. The category id arrives as a raw string and is parsed by {@link AdminInput}; a bad or
 * unknown id renders the admin 404. {@link CategoryNameTakenException} is caught here, after the service
 * transaction has ended, and becomes an error under the name field. Redirects are built from the parsed id only.
 */
@Controller
@RequestMapping("/admin/categories")
class AdminCategoryController {

    private static final Logger log = LoggerFactory.getLogger(AdminCategoryController.class);

    private static final String SUCCESS = "success";
    private static final String ERROR = "error";

    private static final String VIEW_LIST = "admin/category-list";
    private static final String VIEW_FORM = "admin/category-form";
    private static final String REDIRECT_LIST = "redirect:/admin/categories";

    private static final String MSG_NAME_TAKEN = "Tên danh mục đã tồn tại.";

    private final CategoryAdminService categoryService;

    AdminCategoryController(CategoryAdminService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("categories", categoryService.list());
        return VIEW_LIST;
    }

    @GetMapping("/new")
    String newForm(Model model) {
        return newFormView(model, CategoryForm.empty());
    }

    @PostMapping
    String create(@AuthenticationPrincipal CurrentUser admin,
                  @Valid @ModelAttribute("form") CategoryForm form, BindingResult result,
                  Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("mode", "new");
            return VIEW_FORM;
        }
        try {
            String name = categoryService.create(admin.getId(), form.toDraft());
            flash(ra, SUCCESS, "Đã thêm danh mục " + name + ".");
            return REDIRECT_LIST;
        } catch (CategoryNameTakenException e) {
            result.rejectValue("name", "taken", MSG_NAME_TAKEN);
            model.addAttribute("mode", "new");
            return VIEW_FORM;
        }
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable String id, Model model) {
        long categoryId = parseId(id);
        model.addAttribute("form", CategoryForm.of(categoryService.get(categoryId)));
        model.addAttribute("mode", "edit");
        model.addAttribute("categoryId", categoryId);
        return VIEW_FORM;
    }

    @PostMapping("/{id}")
    String update(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id,
                  @Valid @ModelAttribute("form") CategoryForm form, BindingResult result,
                  Model model, RedirectAttributes ra) {
        long categoryId = parseId(id);
        if (!result.hasErrors()) {
            try {
                String name = categoryService.update(admin.getId(), categoryId, form.toDraft());
                flash(ra, SUCCESS, "Đã cập nhật danh mục " + name + ".");
                return REDIRECT_LIST;
            } catch (CategoryNameTakenException e) {
                result.rejectValue("name", "taken", MSG_NAME_TAKEN);
            }
        } else {
            categoryService.get(categoryId); // a missing category is a 404, not a form with errors
        }
        model.addAttribute("mode", "edit");
        model.addAttribute("categoryId", categoryId);
        return VIEW_FORM;
    }

    @PostMapping("/{id}/delete")
    String delete(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id, RedirectAttributes ra) {
        CategoryDeleteResult result = categoryService.delete(admin.getId(), parseId(id));
        switch (result) {
            case CategoryDeleteResult.Deleted d -> flash(ra, SUCCESS, "Đã xóa danh mục " + d.name() + ".");
            case CategoryDeleteResult.InUse u -> flash(ra, ERROR, "Không thể xóa danh mục " + u.name() + " vì còn "
                    + u.productCount() + " sản phẩm (kể cả sản phẩm đã ẩn).");
        }
        return REDIRECT_LIST;
    }

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e, Model model) {
        log.debug("Admin category not found: {}", e.getMessage());
        model.addAttribute("notFoundTitle", "Không tìm thấy danh mục");
        model.addAttribute("backUrl", "/admin/categories");
        model.addAttribute("backLabel", "Quay lại danh sách danh mục");
        model.addAttribute("section", "categories");
        return "admin/not-found";
    }

    private static String newFormView(Model model, CategoryForm form) {
        model.addAttribute("form", form);
        model.addAttribute("mode", "new");
        return VIEW_FORM;
    }

    private static long parseId(String raw) {
        return AdminInput.id(raw).orElseThrow(() -> new NotFoundException("Category id is not valid"));
    }

    private static void flash(RedirectAttributes ra, String kind, String text) {
        ra.addFlashAttribute("notice", text);
        ra.addFlashAttribute("noticeKind", kind);
    }
}
