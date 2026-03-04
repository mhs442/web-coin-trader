package com.coin.webcointrader.config;

import com.coin.webcointrader.login.service.LoginService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@EnableWebSecurity
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginService loginService) throws Exception {
        http.authorizeHttpRequests(auth ->
                        auth.requestMatchers("/login", "/signup", "/_resources/**").permitAll()
                                .anyRequest().authenticated())  // 그 외는 모두 인증 필요
                .formLogin(formLogin ->
                        formLogin.loginPage("/login")
                                .loginProcessingUrl("/login")
                                .defaultSuccessUrl("/dashboard")
                                .usernameParameter("phoneNumber")
                                .permitAll())
                .logout(logout ->
                        logout.logoutUrl("/logout").permitAll())
                .userDetailsService(loginService);

        return http.build();
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
