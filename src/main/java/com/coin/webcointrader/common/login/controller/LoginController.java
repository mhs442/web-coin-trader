package com.coin.webcointrader.common.login.controller;

import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.SignupRequest;
import com.coin.webcointrader.common.login.service.LoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class LoginController {
    private final LoginService loginService;
    private final ExchangeClientRegistry registry;

    /** 회원가입 화면의 거래소 목록 — 구현이 끝난 거래소만 노출 (GET·에러 재렌더 공통) */
    @ModelAttribute("exchangeTypes")
    public List<ExchangeType> exchangeTypes() {
        return registry.supportedTypes();
    }

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
