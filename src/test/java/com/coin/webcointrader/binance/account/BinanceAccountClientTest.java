package com.coin.webcointrader.binance.account;

import com.coin.webcointrader.binance.account.dto.BinanceAccountResponse;
import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Binance 계정 클라이언트 단위 테스트.
 * 계정 정보(v3) 응답을 공통 잔고 DTO로 변환하는지, API 키 바인딩·정리와 키 검증을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BinanceAccountClientTest {

    private static final String FILES = "src/test/resources/__files/";

    @InjectMocks
    private BinanceAccountClient binanceAccountClient;

    @Mock
    private BinanceAccountFeignClient feignClient;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("getExchangeType: BINANCE")
    void exchangeType() {
        assertThat(binanceAccountClient.getExchangeType()).isEqualTo(ExchangeType.BINANCE);
    }

    // ─── getWalletBalance ───

    @Test
    @DisplayName("getWalletBalance: USDT 자산의 marginBalance·walletBalance·availableBalance를 총 자산·지갑·가용 잔고(USDT)로 변환한다")
    void getWalletBalance_mapsUsdtAsset() throws Exception {
        givenKey(1L);
        given(feignClient.getAccount()).willReturn(fixture());

        WalletBalanceDTO result = binanceAccountClient.getWalletBalance(1L);

        assertThat(result.getCurrency()).isEqualTo("USDT");
        assertThat(result.getTotalEquity()).isEqualTo("1010.75000000");
        assertThat(result.getWalletBalance()).isEqualTo("1000.25000000");
        // 계정 단위 availableBalance(960.75)가 아니라 USDT 자산 값
        assertThat(result.getAvailableBalance()).isEqualTo("910.25000000");
    }

    @Test
    @DisplayName("getWalletBalance: 조회 중에는 복호화한 키를 컨텍스트에 두고, 끝나면 정리한다")
    void getWalletBalance_bindsAndClearsKey() throws Exception {
        givenKey(1L);
        AtomicReference<String> keyDuringCall = new AtomicReference<>();
        BinanceAccountResponse response = fixture();
        given(feignClient.getAccount()).willAnswer(inv -> {
            keyDuringCall.set(UserApiKeyContext.getApiKey());
            return response;
        });

        binanceAccountClient.getWalletBalance(1L);

        assertThat(keyDuringCall.get()).isEqualTo("rawKey");
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: USDT 자산이 없으면 금액은 null, 단위만 USDT")
    void getWalletBalance_noUsdt() {
        givenKey(1L);
        BinanceAccountResponse.Asset bnb = new BinanceAccountResponse.Asset();
        bnb.setAsset("BNB");
        bnb.setWalletBalance("0.1");
        BinanceAccountResponse response = new BinanceAccountResponse();
        response.setAssets(List.of(bnb));
        given(feignClient.getAccount()).willReturn(response);

        WalletBalanceDTO result = binanceAccountClient.getWalletBalance(1L);

        assertThat(result.getCurrency()).isEqualTo("USDT");
        assertThat(result.getTotalEquity()).isNull();
        assertThat(result.getWalletBalance()).isNull();
        assertThat(result.getAvailableBalance()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: Binance 오류 응답이면 잔고 조회 실패 예외로 감싸고 키를 정리한다")
    void getWalletBalance_apiError() {
        givenKey(1L);
        given(feignClient.getAccount()).willThrow(new BinanceApiException(-2015, "Invalid API-key, IP, or permissions for action."));

        assertThatThrownBy(() -> binanceAccountClient.getWalletBalance(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getExceptionMessage())
                        .isEqualTo(ExceptionMessage.GET_WALLET_BALANCE_FAILED))
                .hasMessageContaining("-2015");
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: 응답이 없으면 잔고 조회 실패 예외")
    void getWalletBalance_nullResponse() {
        givenKey(1L);
        given(feignClient.getAccount()).willReturn(null);

        assertThatThrownBy(() -> binanceAccountClient.getWalletBalance(1L))
                .isInstanceOf(CustomException.class);
    }

    // ─── validateApiKey ───

    @Test
    @DisplayName("validateApiKey: 입력 키로 계정 조회가 성공하면 true, 호출 중에만 키를 컨텍스트에 둔다")
    void validateApiKey_success() throws Exception {
        AtomicReference<String> keyDuringCall = new AtomicReference<>();
        BinanceAccountResponse response = fixture();
        given(feignClient.getAccount()).willAnswer(inv -> {
            keyDuringCall.set(UserApiKeyContext.getApiKey());
            return response;
        });

        assertThat(binanceAccountClient.validateApiKey("plainKey", "plainSecret")).isTrue();
        assertThat(keyDuringCall.get()).isEqualTo("plainKey");
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    @Test
    @DisplayName("validateApiKey: 호출이 실패하면(키 오류·네트워크 오류 등) false")
    void validateApiKey_failure() {
        given(feignClient.getAccount()).willThrow(new BinanceApiException(-2014, "API-key format invalid."));

        assertThat(binanceAccountClient.validateApiKey("badKey", "badSecret")).isFalse();
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    @Test
    @DisplayName("validateApiKey: 응답이 없으면 false")
    void validateApiKey_nullResponse() {
        given(feignClient.getAccount()).willReturn(null);

        assertThat(binanceAccountClient.validateApiKey("k", "s")).isFalse();
    }

    // ─── 헬퍼 ───

    private BinanceAccountResponse fixture() throws Exception {
        return objectMapper.readValue(new File(FILES + "BinanceAccountClient_getAccount.json"), BinanceAccountResponse.class);
    }

    private void givenKey(Long userId) {
        ExchangeKeyDTO key = ExchangeKeyDTO.builder()
                .userId(userId).exchangeType(ExchangeType.BINANCE)
                .apiKey("encKey").apiSecret("encSecret").build();
        given(exchangeKeyService.findKey(userId, ExchangeType.BINANCE)).willReturn(key);
        given(aesEncryptor.decrypt("encKey")).willReturn("rawKey");
        given(aesEncryptor.decrypt("encSecret")).willReturn("rawSecret");
    }
}
