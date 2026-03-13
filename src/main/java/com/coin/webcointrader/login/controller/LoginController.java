package com.coin.webcointrader.login.controller;

import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.dto.SignupRequest;
import com.coin.webcointrader.login.service.LoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class LoginController {
    private final LoginService loginService;

    @GetMapping("/login")
    public String login() {
        return "login/login-page";
    }

    @GetMapping("/signup")
    public String signupPage() {
        return "signup/signup-page";
    }

    @PostMapping("/signup")
    public String signup(SignupRequest request, Model model) {
        try {
            loginService.signup(request);
            return "redirect:/login?signupSuccess";
        } catch (CustomException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("request", request);
            return "signup/signup-page";
        }
    }
}
