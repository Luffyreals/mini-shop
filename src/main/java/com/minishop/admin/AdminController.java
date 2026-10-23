package com.minishop.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Placeholder admin landing page; replaced by the real dashboard in phase 6. */
@Controller
class AdminController {

    @GetMapping("/admin")
    String index() {
        return "admin/index";
    }
}
