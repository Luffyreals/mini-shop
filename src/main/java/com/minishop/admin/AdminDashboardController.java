package com.minishop.admin;

import com.minishop.catalog.ProductAdminService;
import com.minishop.order.OrderAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin dashboard. Two read-only transactions: order figures (counts, revenue, latest orders) come from
 * one in {@code order}, the low-stock list from one in {@code catalog}, read right after (BR-006-14).
 */
@Controller
class AdminDashboardController {

    private final OrderAdminService orderAdminService;
    private final ProductAdminService productAdminService;

    AdminDashboardController(OrderAdminService orderAdminService, ProductAdminService productAdminService) {
        this.orderAdminService = orderAdminService;
        this.productAdminService = productAdminService;
    }

    @GetMapping("/admin")
    String index(Model model) {
        model.addAttribute("orderStats", orderAdminService.dashboard());
        model.addAttribute("lowStock", productAdminService.lowStock());
        return "admin/index";
    }
}
