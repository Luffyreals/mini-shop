package com.minishop.common;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Target of {@code accessDeniedPage("/access-denied")}. Security forwards here keeping the original HTTP method,
 * so it accepts every method.
 */
@Controller
class AccessDeniedController {

    @RequestMapping("/access-denied")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    String accessDenied() {
        return "error/403";
    }
}
