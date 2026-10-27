package com.coin.webcointrader.common.wallet.controller;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.login.repository.LoginRepository;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import com.coin.webcointrader.common.sim.entity.SimWallet;
import com.coin.webcointrader.common.sim.repository.SimWalletRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 지갑 잔고 API 통합 테스트.
 * 실전(MAIN)은 거래소 응답을, 모의(SIM)는 가상 지갑을 거래소 중립 형식으로 반환하는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class WalletApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRepository loginRepository;

    @Autowired
    private UserExchangeKeyRepository userExchangeKeyRepository;

    @Autowired
    private SimWalletRepository simWalletRepository;

    @Autowired
    private AesEncryptor aesEncryptor;

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        // 백그라운드 스케줄러(MarketService) 대응 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));

        // 지갑 잔고 스텁 (AccountClient → GET /v5/account/wallet-balance)
        stubFor(WireMock.get(urlPathEqualTo("/v5/account/wallet-balance"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("AccountClient_getWalletBalance.json")));

        User user = new User();
        user.setUsername("holder");
        user.setPhoneNumber("01077776666");
        user.setEmail("holder@test.com");
        user.setPassword("encodedPw");
        User saved = loginRepository.save(user);

        // 계정 클라이언트가 복호화할 수 있도록 AES 암호화해 저장
        UserExchangeKey exchangeKey = new UserExchangeKey();
        exchangeKey.setUserId(saved.getId());
        exchangeKey.setExchangeType(ExchangeType.BYBIT);
        exchangeKey.setApiKey(aesEncryptor.encrypt("testApiKey"));
        exchangeKey.setApiSecret(aesEncryptor.encrypt("testApiSecret"));
        userExchangeKeyRepository.save(exchangeKey);

        SimWallet wallet = new SimWallet();
        wallet.setUser(saved);
        wallet.setBalance(new BigDecimal("10500.50"));
        simWalletRepository.save(wallet);

        UserDTO userDTO = UserDTO.builder()
                .id(saved.getId())
                .phoneNumber("01077776666")
                .username("holder")
                .password("encodedPw")
                .build();
        auth = new UsernamePasswordAuthenticationToken(userDTO, null, userDTO.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        simWalletRepository.deleteAll();
        userExchangeKeyRepository.deleteAll();
        loginRepository.deleteAll();
        WireMock.reset();
    }

    @Test
    @DisplayName("GET /api/wallet/balance: MAIN은 Bybit UNIFIED 잔고를 거래소 중립 형식으로 반환한다")
    void getBalance_main() throws Exception {
        mockMvc.perform(get("/api/wallet/balance").param("exchange", "BYBIT").param("mode", "main").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEquity").value("1234.56")) // USDT 코인 equity (계좌 totalEquity 1300.00 아님)
                .andExpect(jsonPath("$.currency").value("USDT"))
                .andExpect(jsonPath("$.walletBalance").value("1200.00"))
                .andExpect(jsonPath("$.availableBalance").value("1104.5")) // 1200.00 - 80 - 15.5 - 0 - 0
                // Bybit 응답 구조는 노출하지 않음
                .andExpect(jsonPath("$.result").doesNotExist());

        // exchange 파라미터 생략 시 BYBIT UNIFIED 계좌 조회
        WireMock.verify(getRequestedFor(urlPathEqualTo("/v5/account/wallet-balance"))
                .withQueryParam("accountType", equalTo("UNIFIED")));
    }

    @Test
    @DisplayName("GET /api/wallet/balance: SIM은 거래소를 호출하지 않고 가상 지갑 잔고를 반환한다")
    void getBalance_sim() throws Exception {
        mockMvc.perform(get("/api/wallet/balance").param("exchange", "BYBIT").param("mode", "sim").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEquity").value("10500.5"))
                .andExpect(jsonPath("$.currency").value("USDT"))
                .andExpect(jsonPath("$.availableBalance").value("10500.5"));

        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/v5/account/wallet-balance")));
    }

    @Test
    @DisplayName("GET /api/wallet/balance: 지원하지 않는 exchange 값이면 400")
    void getBalance_invalidExchange() throws Exception {
        mockMvc.perform(get("/api/wallet/balance").param("exchange", "UNKNOWN").with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }
}
