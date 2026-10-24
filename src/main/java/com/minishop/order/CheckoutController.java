package com.minishop.order;

import com.minishop.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Checkout page and order placement. The user id always comes from the principal; {@code expectedTotal}
 * arrives as a raw string (not part of {@link CheckoutForm}) and is only compared, never stored. The
 * {@link PlaceOrderException} outcomes are caught here, after the service transaction has ended.
 */
@Controller
@RequestMapping("/checkout")
class CheckoutController {

    private static final String SUCCESS = "success";
    private static final String INFO = "info";
    private static final String ERROR = "error";

    private static final String VIEW_CHECKOUT = "order/checkout";
    private static final String REDIRECT_CART = "redirect:/cart";
    private static final String REDIRECT_ORDERS = "redirect:/orders";

    private static final String MSG_NOT_ORDERABLE =
            "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng.";
    private static final String MSG_EMPTY_CART = "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo.";
    private static final String MSG_PRICE_CHANGED =
            "Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận.";

    private final OrderService orderService;

    CheckoutController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    String show(@AuthenticationPrincipal CurrentUser user, Model model, RedirectAttributes ra) {
        CheckoutView view = orderService.prepareCheckout(user.getId());
        String redirect = redirectIfNotCheckoutable(view, ra);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("checkout", view);
        model.addAttribute("form", orderService.prefilledForm(user.getId()));
        return VIEW_CHECKOUT;
    }

    @PostMapping
    String place(@AuthenticationPrincipal CurrentUser user,
                 @Valid @ModelAttribute("form") CheckoutForm form, BindingResult result,
                 @RequestParam(required = false) String expectedTotal,
                 Model model, RedirectAttributes ra) {
        BigDecimal expected = OrderInput.amount(expectedTotal).orElse(null);

        if (result.hasErrors()) {
            // Re-render with the current prices; placeOrder is not called.
            CheckoutView view = orderService.prepareCheckout(user.getId());
            String redirect = redirectIfNotCheckoutable(view, ra);
            if (redirect != null) {
                return redirect;
            }
            model.addAttribute("checkout", view);
            if (view.totalDiffersFrom(expected)) {
                priceChangedNotice(model);
            }
            return VIEW_CHECKOUT;
        }

        try {
            long orderId = orderService.placeOrder(user.getId(), form, expected);
            flash(ra, SUCCESS, "Đặt hàng thành công. Mã đơn của bạn là #" + orderId + ".");
            return "redirect:/orders/" + orderId;
        } catch (PlaceOrderException e) {
            return switch (e) {
                case PlaceOrderException.EmptyCart ignored -> {
                    flash(ra, INFO, MSG_EMPTY_CART);
                    yield REDIRECT_ORDERS;
                }
                case PlaceOrderException.NotOrderable ignored -> {
                    flash(ra, ERROR, MSG_NOT_ORDERABLE);
                    yield REDIRECT_CART;
                }
                case PlaceOrderException.InsufficientStock s -> {
                    flash(ra, ERROR, "Sản phẩm " + s.productName() + " không đủ hàng. Vui lòng cập nhật giỏ hàng.");
                    yield REDIRECT_CART;
                }
                case PlaceOrderException.PriceChanged p -> {
                    model.addAttribute("checkout", p.current());
                    priceChangedNotice(model);
                    yield VIEW_CHECKOUT;
                }
            };
        }
    }

    /** Empty cart goes to /cart silently; a non-orderable cart goes there with the error. Null = stay. */
    private static String redirectIfNotCheckoutable(CheckoutView view, RedirectAttributes ra) {
        if (view.isEmpty()) {
            return REDIRECT_CART;
        }
        if (!view.orderable()) {
            flash(ra, ERROR, MSG_NOT_ORDERABLE);
            return REDIRECT_CART;
        }
        return null;
    }

    private static void priceChangedNotice(Model model) {
        model.addAttribute("notice", MSG_PRICE_CHANGED);
        model.addAttribute("noticeKind", ERROR);
    }

    private static void flash(RedirectAttributes ra, String kind, String text) {
        ra.addFlashAttribute("notice", text);
        ra.addFlashAttribute("noticeKind", kind);
    }
}
