package com.minishop.admin;

import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryNotFoundException;
import com.minishop.catalog.AdminProductPage;
import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.SavedProduct;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.common.FormText;
import com.minishop.common.NotFoundException;
import com.minishop.common.PageNav;
import com.minishop.storage.ImageUpload;
import com.minishop.storage.InvalidImageException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Admin product pages. Ids, page and the stock delta arrive as raw strings and are parsed by {@link AdminInput};
 * a bad or unknown id renders the admin 404. The image is read with {@link ImageUpload#from} even when other
 * fields have errors, so every error shows at once; with any error the service is not called and no file is
 * written. {@link CategoryNotFoundException} is caught here, after the service transaction has ended. Redirects
 * are built from the parsed id and the cleaned {@code q}, never from Referer.
 */
@Controller
@RequestMapping("/admin/products")
class AdminProductController {

    private static final Logger log = LoggerFactory.getLogger(AdminProductController.class);

    private static final String SUCCESS = "success";
    private static final String ERROR = "error";

    private static final String VIEW_LIST = "admin/product-list";
    private static final String VIEW_NEW = "admin/product-new";
    private static final String VIEW_EDIT = "admin/product-edit";

    private static final String MSG_CATEGORY_MISSING = "Danh mục không tồn tại.";
    private static final String MSG_IMAGE_TYPE = "Ảnh chỉ được là JPG, PNG hoặc WebP.";
    private static final String MSG_IMAGE_SIZE = "Ảnh tối đa 2 MB.";
    private static final String MSG_DELTA_INVALID =
            "Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.";
    private static final String MSG_STOCK_EXCEEDS = "Tồn kho sau điều chỉnh không được vượt quá 100.000.";

    private static final int MAX_QUERY_LENGTH = 100;

    private final ProductAdminService productService;
    private final CategoryAdminService categoryService;

    AdminProductController(ProductAdminService productService, CategoryAdminService categoryService) {
        this.productService = productService;
        this.categoryService = categoryService;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, @RequestParam(required = false) String page,
                Model model) {
        AdminProductPage products = productService.list(q, page);
        model.addAttribute("products", products);
        model.addAttribute("pageNav",
                PageNav.of(products.page(), products.totalPages(), p -> listUrl(products.q(), p)));
        return VIEW_LIST;
    }

    @GetMapping("/new")
    String newForm(Model model) {
        model.addAttribute("form", NewProductForm.empty());
        model.addAttribute("categories", categoryService.options());
        return VIEW_NEW;
    }

    @PostMapping
    String create(@AuthenticationPrincipal CurrentUser admin,
                  @Valid @ModelAttribute("form") NewProductForm form, BindingResult result,
                  Model model, RedirectAttributes ra) {
        ImageUpload image = readImage(form.image(), result);
        if (!result.hasErrors()) {
            try {
                SavedProduct saved = productService.create(admin.getId(), form.toDraft(), form.initialStockValue(),
                        form.isActiveChecked(), image);
                flash(ra, SUCCESS, "Đã thêm sản phẩm " + saved.name() + ".");
                return "redirect:/admin/products/" + saved.id() + "/edit";
            } catch (CategoryNotFoundException e) {
                result.rejectValue("categoryId", "missing", MSG_CATEGORY_MISSING);
            }
        }
        model.addAttribute("categories", categoryService.options());
        return VIEW_NEW;
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable String id, Model model) {
        var product = productService.getForEdit(parseId(id));
        model.addAttribute("product", product);
        model.addAttribute("form", EditProductForm.of(product));
        model.addAttribute("categories", categoryService.options());
        return VIEW_EDIT;
    }

    @PostMapping("/{id}")
    String update(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id,
                  @Valid @ModelAttribute("form") EditProductForm form, BindingResult result,
                  Model model, RedirectAttributes ra) {
        long productId = parseId(id);
        ImageUpload image = readImage(form.image(), result);
        if (!result.hasErrors()) {
            try {
                String name = productService.update(admin.getId(), productId, form.toDraft(), image);
                flash(ra, SUCCESS, "Đã cập nhật sản phẩm " + name + ".");
                return editRedirect(productId);
            } catch (CategoryNotFoundException e) {
                result.rejectValue("categoryId", "missing", MSG_CATEGORY_MISSING);
            }
        }
        // Also the 404 for an unknown product when the form had errors.
        model.addAttribute("product", productService.getForEdit(productId));
        model.addAttribute("categories", categoryService.options());
        return VIEW_EDIT;
    }

    @PostMapping("/{id}/hide")
    String hide(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id,
                @RequestParam(required = false) String from, @RequestParam(required = false) String q,
                @RequestParam(required = false) String page, RedirectAttributes ra) {
        long productId = parseId(id);
        String name = productService.setActive(admin.getId(), productId, false);
        flash(ra, SUCCESS, "Đã ẩn sản phẩm " + name + ". Khách sẽ không thấy sản phẩm này nữa.");
        return backTo(productId, from, q, page);
    }

    @PostMapping("/{id}/show")
    String show(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id,
                @RequestParam(required = false) String from, @RequestParam(required = false) String q,
                @RequestParam(required = false) String page, RedirectAttributes ra) {
        long productId = parseId(id);
        String name = productService.setActive(admin.getId(), productId, true);
        flash(ra, SUCCESS, "Đã hiện lại sản phẩm " + name + ".");
        return backTo(productId, from, q, page);
    }

    @PostMapping("/{id}/stock")
    String stock(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id,
                 @RequestParam(required = false) String delta, RedirectAttributes ra) {
        long productId = parseId(id);
        var parsed = AdminInput.stockDelta(delta);
        if (parsed.isEmpty()) {
            flash(ra, ERROR, MSG_DELTA_INVALID);
            return editRedirect(productId);
        }
        StockAdjustResult result = productService.adjustStock(admin.getId(), productId, parsed.get());
        switch (result) {
            case StockAdjustResult.Adjusted a -> flash(ra, SUCCESS, "Đã cập nhật tồn kho sản phẩm " + a.name()
                    + ". Tồn kho hiện tại: " + a.stock() + ".");
            case StockAdjustResult.NotEnough n -> flash(ra, ERROR, "Không thể giảm " + n.requested()
                    + " vì tồn kho hiện tại chỉ còn " + n.current() + ".");
            case StockAdjustResult.WouldExceed ignored -> flash(ra, ERROR, MSG_STOCK_EXCEEDS);
        }
        return editRedirect(productId);
    }

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e, Model model) {
        log.debug("Admin product not found: {}", e.getMessage());
        model.addAttribute("notFoundTitle", "Không tìm thấy sản phẩm");
        model.addAttribute("backUrl", "/admin/products");
        model.addAttribute("backLabel", "Quay lại danh sách sản phẩm");
        model.addAttribute("section", "products");
        return "admin/not-found";
    }

    /** Reads the upload; an invalid image becomes an error under the image field and yields null. */
    private static ImageUpload readImage(MultipartFile file, BindingResult result) {
        if (file == null) {
            return null;
        }
        try {
            return ImageUpload.from(file.getOriginalFilename(), file.getContentType(), file.getSize(), file)
                    .orElse(null);
        } catch (InvalidImageException e) {
            String message = switch (e.reason()) {
                case UNSUPPORTED_TYPE -> MSG_IMAGE_TYPE;
                case TOO_LARGE -> MSG_IMAGE_SIZE;
            };
            result.rejectValue("image", e.reason().name(), message);
            return null;
        }
    }

    /** "from=edit" goes back to the edit page; anything else goes back to the list, keeping q and page. */
    private static String backTo(long productId, String from, String q, String page) {
        if ("edit".equals(from)) {
            return editRedirect(productId);
        }
        String cleaned = FormText.clean(q, false);
        if (cleaned != null && cleaned.codePointCount(0, cleaned.length()) > MAX_QUERY_LENGTH) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_QUERY_LENGTH));
        }
        return "redirect:" + listUrl(FormText.blankToNull(cleaned), AdminInput.page(page));
    }

    private static String editRedirect(long productId) {
        return "redirect:/admin/products/" + productId + "/edit";
    }

    /** encode() before buildAndExpand so "+" and Vietnamese letters are percent-encoded strictly. */
    private static String listUrl(String q, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/products");
        if (q != null) {
            builder.queryParam("q", "{q}");
        }
        builder.queryParam("page", "{page}");
        return q != null
                ? builder.encode().buildAndExpand(q, page).toUriString()
                : builder.encode().buildAndExpand(page).toUriString();
    }

    private static long parseId(String raw) {
        return AdminInput.id(raw).orElseThrow(() -> new NotFoundException("Product id is not valid"));
    }

    private static void flash(RedirectAttributes ra, String kind, String text) {
        ra.addFlashAttribute("notice", text);
        ra.addFlashAttribute("noticeKind", kind);
    }
}
