package com.coin.webcointrader.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest
@RequiredArgsConstructor
@Slf4j
public class SecurityConfigTest {
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @Test
    public void encodeTest(){
        bCryptPasswordEncoder = new BCryptPasswordEncoder();
        log.error("this is encoding password : {}", bCryptPasswordEncoder.encode("01026419389"));
        //insert into users (password, phone_number, username) values('$2a$10$aNu8NEM4IFrQnVsSyJgyIet1Vmqbh.AXIjWSp4qlI5x4SYL/LzebO', '01026419389', '문햇살');
    }
}
