package com.coin.webcointrader.signup.controller;

import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.signup.dto.SignupRequest;
import com.coin.webcointrader.signup.service.SignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class SignupController {

    private final SignupService signupService;

    @GetMapping("/signup")
    public String signupPage() {
        return "signup/signup-page";
    }

    @PostMapping("/signup")
    public String signup(SignupRequest request, Model model) {
        try {
            signupService.signup(request);
            return "redirect:/login?signupSuccess";
        } catch (CustomException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("request", request);
            return "signup/signup-page";
        }
    }
}
