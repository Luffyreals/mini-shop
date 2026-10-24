package com.minishop.cart;

import com.minishop.common.NotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.OptionalInt;

/**
 * Cart pages. Parameters arrive as raw strings and are parsed by {@link CartInput}; redirects are built from
 * the parsed id only (never from Referer or a "return to" parameter).
 */
@Controller
@RequestMapping("/cart")
class CartController {

    private static final String SUCCESS = "success";
    private static final String INFO = "info";
    private static final String ERROR = "error";

    private static final String MSG_ADDED = "Đã thêm vào giỏ hàng.";
    private static final String MSG_UPDATED = "Đã cập nhật số lượng.";
    private static final String MSG_REMOVED = "Đã xóa sản phẩm khỏi giỏ hàng.";
    private static final String MSG_OUT_OF_STOCK = "Sản phẩm này đã hết hàng.";
    private static final String MSG_INVALID_QUANTITY = "Số lượng không hợp lệ.";
    private static final String MSG_MAX = "Số lượng tối đa cho mỗi sản phẩm là " + CartService.MAX_LINE_QUANTITY + ".";

    private final CartService cartService;

    CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    String view(CartOwner owner, Model model) {
        model.addAttribute("cart", cartService.view(owner));
        return "cart/view";
    }

    @PostMapping("/items")
    String add(CartOwner owner, @RequestParam(required = false) String productId,
               @RequestParam(required = false) String quantity, RedirectAttributes ra) {
        long id = parseProductId(productId);
        String back = "redirect:/products/" + id;
        OptionalInt q = CartInput.quantity(quantity);
        if (q.isEmpty()) {
            flash(ra, ERROR, MSG_INVALID_QUANTITY);
            return back;
        }
        CartChange change = cartService.add(owner, id, q.getAsInt());
        switch (change.outcome()) {
            case OK -> flash(ra, SUCCESS, MSG_ADDED, true);
            case CAPPED_AT_STOCK -> flash(ra, INFO, stockMessage(change.quantity()), true);
            case CAPPED_AT_MAX -> flash(ra, INFO, MSG_MAX, true);
            case OUT_OF_STOCK -> flash(ra, ERROR, MSG_OUT_OF_STOCK);
            case NOT_IN_CART -> { /* cannot happen when adding */ }
        }
        return back;
    }

    @PostMapping("/items/{productId}")
    String update(CartOwner owner, @PathVariable String productId,
                  @RequestParam(required = false) String quantity, RedirectAttributes ra) {
        long id = parseProductId(productId);
        OptionalInt q = CartInput.quantity(quantity);
        if (q.isEmpty()) {
            flash(ra, ERROR, MSG_INVALID_QUANTITY);
            return "redirect:/cart";
        }
        CartChange change = cartService.update(owner, id, q.getAsInt());
        switch (change.outcome()) {
            case OK -> flash(ra, SUCCESS, MSG_UPDATED);
            case CAPPED_AT_STOCK -> flash(ra, INFO, stockMessage(change.quantity()));
            case CAPPED_AT_MAX -> flash(ra, INFO, MSG_MAX);
            case OUT_OF_STOCK -> flash(ra, ERROR, MSG_OUT_OF_STOCK);
            case NOT_IN_CART -> { /* no message */ }
        }
        return "redirect:/cart";
    }

    @PostMapping("/items/{productId}/delete")
    String remove(CartOwner owner, @PathVariable String productId, RedirectAttributes ra) {
        cartService.remove(owner, parseProductId(productId));
        flash(ra, SUCCESS, MSG_REMOVED);
        return "redirect:/cart";
    }

    private static long parseProductId(String raw) {
        return CartInput.productId(raw)
                .orElseThrow(() -> new NotFoundException("Product not found: " + raw));
    }

    private static String stockMessage(int stock) {
        return "Chỉ còn " + stock + " sản phẩm trong kho.";
    }

    private static void flash(RedirectAttributes ra, String kind, String text) {
        ra.addFlashAttribute("notice", text);
        ra.addFlashAttribute("noticeKind", kind);
    }

    private static void flash(RedirectAttributes ra, String kind, String text, boolean showCartLink) {
        flash(ra, kind, text);
        ra.addFlashAttribute("showCartLink", showCartLink);
    }
}
