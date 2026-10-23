package com.minishop.user;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
class AuthController {

    private static final String REDIRECT_HOME = "redirect:/";

    private final UserService userService;

    AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/login")
    String login(Principal principal) {
        return principal != null ? REDIRECT_HOME : "user/login";
    }

    @GetMapping("/register")
    String registerForm(Principal principal, Model model) {
        if (principal != null) {
            return REDIRECT_HOME;
        }
        model.addAttribute("form", RegistrationForm.empty());
        return "user/register";
    }

    @PostMapping("/register")
    String register(Principal principal, @Valid @ModelAttribute("form") RegistrationForm form,
                    BindingResult result, RedirectAttributes redirect) {
        if (principal != null) {
            return REDIRECT_HOME;
        }
        if (result.hasErrors()) {
            return "user/register";
        }
        try {
            userService.register(form);
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "duplicate", "Email này đã được đăng ký.");
            return "user/register";
        }
        redirect.addFlashAttribute("registered", true);
        return "redirect:/login";
    }
}
