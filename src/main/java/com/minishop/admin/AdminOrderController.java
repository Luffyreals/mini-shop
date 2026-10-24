package com.minishop.admin;

import com.minishop.common.NotFoundException;
import com.minishop.common.NotFoundPage;
import com.minishop.common.PageNav;
import com.minishop.order.AdminOrderPage;
import com.minishop.order.OrderAdminService;
import com.minishop.order.StatusChangeResult;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Admin order pages. The status filter, the target status and the page are passed to the service as raw
 * strings: only {@code order} knows the status table, so no rule about transitions lives here. The
 * {@link StatusChangeResult} is mapped with an exhaustive pattern switch.
 */
@Controller
@NotFoundPage(view = "admin/not-found", title = "Không tìm thấy đơn hàng", backUrl = "/admin/orders",
        backLabel = "Quay lại danh sách đơn", section = "orders")
@RequestMapping("/admin/orders")
class AdminOrderController {

    private static final String SUCCESS = "success";
    private static final String ERROR = "error";

    private static final String MSG_INVALID_STATUS = "Trạng thái không hợp lệ.";

    private final OrderAdminService orderService;

    AdminOrderController(OrderAdminService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    String list(@RequestParam(required = false) String status, @RequestParam(required = false) String page,
                Model model) {
        AdminOrderPage orders = orderService.listOrders(status, page);
        model.addAttribute("orders", orders);
        model.addAttribute("pageNav",
                PageNav.of(orders.page(), orders.totalPages(), p -> listUrl(orders.status(), p)));
        return "admin/order-list";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable String id, Model model) {
        model.addAttribute("order", orderService.getOrder(parseId(id)));
        return "admin/order-detail";
    }

    @PostMapping("/{id}/status")
    String changeStatus(@AuthenticationPrincipal CurrentUser admin, @PathVariable String id,
                        @RequestParam(required = false) String status, RedirectAttributes ra) {
        long orderId = parseId(id);
        StatusChangeResult result = orderService.changeStatus(admin.getId(), orderId, status);
        switch (result) {
            case StatusChangeResult.Changed c -> flash(ra, SUCCESS,
                    "Đã chuyển đơn hàng #" + orderId + " sang trạng thái " + c.statusLabel() + ".");
            case StatusChangeResult.Cancelled ignored ->
                    flash(ra, SUCCESS, "Đã hủy đơn hàng #" + orderId + " và hoàn lại tồn kho.");
            case StatusChangeResult.NotAllowed n -> flash(ra, ERROR, "Không thể chuyển đơn hàng #" + orderId
                    + " từ " + n.fromLabel() + " sang " + n.toLabel() + ".");
            case StatusChangeResult.InvalidStatus ignored -> flash(ra, ERROR, MSG_INVALID_STATUS);
        }
        return "redirect:/admin/orders/" + orderId;
    }

    /** {@code status} is the service's normalized filter (an enum name or null), so it keeps the filter in links. */
    private static String listUrl(String status, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/orders");
        if (status != null) {
            builder.queryParam("status", "{status}");
        }
        builder.queryParam("page", "{page}");
        return status != null
                ? builder.encode().buildAndExpand(status, page).toUriString()
                : builder.encode().buildAndExpand(page).toUriString();
    }

    private static long parseId(String raw) {
        return AdminInput.id(raw).orElseThrow(() -> new NotFoundException("Order id is not valid"));
    }

    private static void flash(RedirectAttributes ra, String kind, String text) {
        ra.addFlashAttribute("notice", text);
        ra.addFlashAttribute("noticeKind", kind);
    }
}
