package com.minishop.catalog;

import com.minishop.common.PageNav;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

@Controller
class ProductController {

    private final ProductService productService;

    ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** Tham số là String thô nên bind không bao giờ lỗi; service chuẩn hóa (BR-002-3). */
    @GetMapping({"/", "/products"})
    String list(@RequestParam(required = false) String q,
                @RequestParam(required = false) String category,
                @RequestParam(required = false) String sort,
                @RequestParam(required = false) String page,
                Model model) {
        ProductListView view = productService.search(new ProductSearchParams(q, category, sort, page));
        model.addAttribute("view", view);
        model.addAttribute("pageNav",
                PageNav.of(view.filter().page(), view.totalPages(), p -> listUrl(view.filter(), p)));
        return "product/list";
    }

    @GetMapping("/products/{id}")
    String detail(@PathVariable Long id, Model model) {
        model.addAttribute("product", productService.getDetail(id));
        return "product/detail";
    }

    /** Dựng từ bộ lọc đã chuẩn hóa; encode() trước buildAndExpand để "+" và dấu tiếng Việt được mã hóa chặt. */
    private static String listUrl(ProductFilter filter, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/products");
        Map<String, Object> values = new HashMap<>();
        if (filter.q() != null) {
            builder.queryParam("q", "{q}");
            values.put("q", filter.q());
        }
        if (filter.categoryId() != null) {
            builder.queryParam("category", "{category}");
            values.put("category", filter.categoryId());
        }
        builder.queryParam("sort", "{sort}").queryParam("page", "{page}");
        values.put("sort", filter.sort().key());
        values.put("page", page);
        return builder.encode().buildAndExpand(values).toUriString();
    }
}
