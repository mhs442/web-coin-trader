package com.coin.webcointrader.bybit.client.trade;

import com.coin.webcointrader.bybit.dto.request.CreateOrderRequest;
import com.coin.webcointrader.bybit.dto.response.GetOpenOrdersResponse;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.github.tomakehurst.wiremock.client.WireMock;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureWireMock(port = 0)
class TradeClientTest {

    @Autowired
    private TradeClient tradeClient;

    @BeforeEach
    void setUp() {
        // 백그라운드 스케줄러(MarketService) 대응 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));
        // 서명 인터셉터가 API Key를 요구하므로 컨텍스트 설정
        UserApiKeyContext.set("testKey", "testSecret");
    }

    @AfterEach
    void tearDown() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("getOpenOrders: orderLinkId로 주문 상태를 조회해 매핑한다")
    void getOpenOrders_orderLinkId조회() {
        // given
        stubFor(WireMock.get(urlPathEqualTo("/v5/order/realtime"))
                .withQueryParam("category", equalTo("linear"))
                .withQueryParam("symbol", equalTo("BTCUSDT"))
                .withQueryParam("orderLinkId", equalTo("q1-E-1700000000000"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("TradeClient_getOpenOrders.json")));

        // when
        GetOpenOrdersResponse response = tradeClient
                .getOpenOrders("linear", "BTCUSDT", "q1-E-1700000000000").getBody();

        // then
        assertThat(response).isNotNull();
        assertThat(response.getRetCode()).isEqualTo("0");
        GetOpenOrdersResponse.OrderInfo order = response.getResult().getList().get(0);
        assertThat(order.getOrderId()).isEqualTo("fd4300ae-7847-404e-b947-b46980a4d140");
        assertThat(order.getOrderLinkId()).isEqualTo("q1-E-1700000000000");
        assertThat(order.getOrderStatus()).isEqualTo("Filled");
        assertThat(order.getCumExecQty()).isEqualTo("0.01");
        assertThat(order.getAvgPrice()).isEqualTo("50000.5");
    }

    @Test
    @DisplayName("createOrder: 응답이 읽기 타임아웃(5초)을 넘으면 RetryableException을 던진다")
    void createOrder_읽기타임아웃() {
        // given - 응답 6초 지연
        stubFor(WireMock.post(urlPathEqualTo("/v5/order/create"))
                .willReturn(aResponse()
                        .withFixedDelay(6000)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("TradeClient_createOrder.json")));
        CreateOrderRequest request = CreateOrderRequest.builder()
                .category("linear").symbol("BTCUSDT").side("Buy").orderType("Market").qty("0.01")
                .build();

        // when & then
        assertThatThrownBy(() -> tradeClient.createOrder(request))
                .isInstanceOf(RetryableException.class);
    }
}
