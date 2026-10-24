package com.minishop.order;

import com.minishop.common.NotFoundException;
import com.minishop.common.NotFoundPage;
import com.minishop.common.PageNav;
import com.minishop.user.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Order history, detail and customer cancel. Ids arrive as raw strings and are parsed by {@link OrderInput};
 * ownership is enforced in the service (an order of someone else is a 404). Redirects are built from the
 * parsed id only, never from Referer.
 */
@Controller
@NotFoundPage(view = "order/not-found")
@RequestMapping("/orders")
class OrderController {

    private static final String SUCCESS = "success";
    private static final String ERROR = "error";

    private static final String MSG_NOT_CANCELLABLE = "Đơn hàng này không thể hủy nữa.";

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    String list(@AuthenticationPrincipal CurrentUser user, @RequestParam(required = false) String page,
                Model model) {
        OrderListView orders = orderService.listOrders(user.getId(), page);
        model.addAttribute("orders", orders);
        model.addAttribute("pageNav", PageNav.of(orders.page(), orders.totalPages(), p -> "/orders?page=" + p));
        return "order/list";
    }

    @GetMapping("/{id}")
    String detail(@AuthenticationPrincipal CurrentUser user, @PathVariable String id, Model model) {
        model.addAttribute("order", orderService.getOrder(user.getId(), parseOrderId(id)));
        return "order/detail";
    }

    @PostMapping("/{id}/cancel")
    String cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable String id, RedirectAttributes ra) {
        long orderId = parseOrderId(id);
        CancelResult result = orderService.cancelByCustomer(user.getId(), orderId);
        switch (result) {
            case CANCELLED -> flash(ra, SUCCESS, "Đã hủy đơn hàng #" + orderId + ".");
            case NOT_CANCELLABLE -> flash(ra, ERROR, MSG_NOT_CANCELLABLE);
        }
        return "redirect:/orders/" + orderId;
    }

    private static long parseOrderId(String raw) {
        return OrderInput.orderId(raw)
                .orElseThrow(() -> new NotFoundException("Order not found: " + raw));
    }

    private static void flash(RedirectAttributes ra, String kind, String text) {
        ra.addFlashAttribute("notice", text);
        ra.addFlashAttribute("noticeKind", kind);
    }
}
