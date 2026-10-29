package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.stream.dto.BinanceListenKeyResponse;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binance listenKey(user data stream 접속 키) API 통합 테스트.
 * 발급·연장·삭제 요청이 키 헤더만 붙여 전송되는지, 응답·에러가 매핑되는지 검증한다.
 */
@SpringBootTest
@AutoConfigureWireMock(port = 0)
class BinanceUserStreamFeignClientTest {

    private static final String LISTEN_KEY_PATH = "/fapi/v1/listenKey";

    @Autowired
    private BinanceUserStreamFeignClient feignClient;

    @BeforeEach
    void setUp() {
        // 키 헤더 인터셉터가 API Key를 요구하므로 컨텍스트 설정
        UserApiKeyContext.set("testKey", "testSecret");
    }

    @AfterEach
    void tearDown() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("createListenKey: POST 를 키 헤더만 붙여(서명 없이) 보내고 listenKey 를 매핑한다")
    void createListenKey() {
        stubFor(WireMock.post(urlPathEqualTo(LISTEN_KEY_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("BinanceUserStreamFeignClient_listenKey.json")));

        BinanceListenKeyResponse response = feignClient.createListenKey();

        assertThat(response.getListenKey())
                .isEqualTo("pqia91ma19a5s61cv6a81va65sdf19v8a65a1a5s61cv6a81va65sdf19v8a65a1");
        verify(postRequestedFor(urlPathEqualTo(LISTEN_KEY_PATH))
                .withHeader("X-MBX-APIKEY", equalTo("testKey"))
                .withQueryParam("signature", absent())
                .withQueryParam("timestamp", absent()));
    }

    @Test
    @DisplayName("keepAliveListenKey: PUT 으로 연장하고 listenKey 를 매핑한다")
    void keepAliveListenKey() {
        stubFor(WireMock.put(urlPathEqualTo(LISTEN_KEY_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("BinanceUserStreamFeignClient_listenKey.json")));

        BinanceListenKeyResponse response = feignClient.keepAliveListenKey();

        assertThat(response.getListenKey())
                .isEqualTo("pqia91ma19a5s61cv6a81va65sdf19v8a65a1a5s61cv6a81va65sdf19v8a65a1");
    }

    @Test
    @DisplayName("keepAliveListenKey: 만료된 키(-1125)는 BinanceApiException 으로 code 를 전달한다 (재발급 판단용)")
    void keepAliveListenKey_notExist() {
        stubFor(WireMock.put(urlPathEqualTo(LISTEN_KEY_PATH))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("BinanceUserStreamFeignClient_listenKeyNotExist.json")));

        assertThatThrownBy(() -> feignClient.keepAliveListenKey())
                .isInstanceOf(BinanceApiException.class)
                .extracting("code").isEqualTo(-1125);
    }

    @Test
    @DisplayName("closeListenKey: DELETE 를 키 헤더와 함께 보낸다")
    void closeListenKey() {
        stubFor(WireMock.delete(urlPathEqualTo(LISTEN_KEY_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        feignClient.closeListenKey();

        verify(deleteRequestedFor(urlPathEqualTo(LISTEN_KEY_PATH))
                .withHeader("X-MBX-APIKEY", equalTo("testKey")));
    }
}
