package com.minishop.catalog;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang chủ tạm của GĐ1. GĐ2 thay bằng trang danh sách sản phẩm.
 */
@Controller
class HomeController {

    @GetMapping("/")
    String home() {
        return "index";
    }
}
