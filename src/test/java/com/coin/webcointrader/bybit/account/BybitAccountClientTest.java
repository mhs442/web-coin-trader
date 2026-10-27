package com.coin.webcointrader.bybit.account;

import com.coin.webcointrader.bybit.account.dto.GetWalletBalanceResponse;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Bybit 계정 클라이언트 단위 테스트.
 * UNIFIED 지갑 응답을 공통 잔고 DTO로 변환하는지, API 키 바인딩·검증 위임을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BybitAccountClientTest {

    @InjectMocks
    private BybitAccountClient bybitAccountClient;

    @Mock
    private AccountClient accountClient;

    @Mock
    private BybitApiKeyValidator bybitApiKeyValidator;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("getExchangeType: BYBIT")
    void exchangeType() {
        assertThat(bybitAccountClient.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    // ─── getWalletBalance ───

    @Test
    @DisplayName("getWalletBalance: USDT 코인 기준 총 자산(equity)·지갑 잔고·격리 마진 가용 잔고(지갑 - 포지션IM - 주문IM - 잠금 - 보너스)와 단위 USDT로 변환한다")
    void getWalletBalance_mapsUnifiedAccount() {
        givenKey(1L);
        GetWalletBalanceResponse.Result.AccountInfo.CoinInfo usdt = coin("USDT", "1000.25", "80", "15.5", "0", "4.25");
        usdt.setEquity("1010.75");
        // 계좌 단위 totalEquity(USD 합계)는 격리 마진에 적용되지 않으므로 쓰지 않는다
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(
                response("0", account("1234.5", coin("BTC", "0.1", null, null, null, null), usdt))));

        WalletBalanceDTO result = bybitAccountClient.getWalletBalance(1L);

        assertThat(result.getCurrency()).isEqualTo("USDT");
        assertThat(result.getTotalEquity()).isEqualTo("1010.75");
        assertThat(result.getWalletBalance()).isEqualTo("1000.25");
        assertThat(result.getAvailableBalance()).isEqualTo("900.5");
    }

    @Test
    @DisplayName("getWalletBalance: 요청 동안 복호화한 사용자 키를 바인딩하고, 끝나면 정리한다")
    void getWalletBalance_bindsAndClearsKey() {
        givenKey(1L);
        given(accountClient.getWalletBalance("UNIFIED")).willAnswer(inv -> {
            // Feign 서명 인터셉터가 읽는 키가 바인딩된 상태여야 함
            assertThat(UserApiKeyContext.getApiKey()).isEqualTo("rawKey");
            assertThat(UserApiKeyContext.getApiSecret()).isEqualTo("rawSecret");
            return ResponseEntity.ok(response("0", account("1", coin("USDT", "1", null, null, null, null))));
        });

        bybitAccountClient.getWalletBalance(1L);

        assertThat(UserApiKeyContext.getApiKey()).isNull();
        assertThat(UserApiKeyContext.getApiSecret()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: USDT 보유 내역이 없으면 세 잔고 모두 null")
    void getWalletBalance_noUsdtCoin() {
        givenKey(1L);
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(
                response("0", account("50", coin("BTC", "0.001", null, null, null, null)))));

        WalletBalanceDTO result = bybitAccountClient.getWalletBalance(1L);

        assertThat(result.getTotalEquity()).isNull();
        assertThat(result.getWalletBalance()).isNull();
        assertThat(result.getAvailableBalance()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: 가용 잔고 계산 시 빈 문자열·null 차감 항목은 0으로 본다")
    void getWalletBalance_blankDeductionsAsZero() {
        givenKey(1L);
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(
                response("0", account("500", coin("USDT", "500", "", "", null, "")))));

        WalletBalanceDTO result = bybitAccountClient.getWalletBalance(1L);

        assertThat(result.getAvailableBalance()).isEqualTo("500");
    }

    @Test
    @DisplayName("getWalletBalance: 계좌 목록이 비어 있으면(UNIFIED 지갑 없음) 모든 값이 null인 잔고를 반환한다")
    void getWalletBalance_emptyAccountList() {
        givenKey(1L);
        GetWalletBalanceResponse empty = response("0");
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(empty));

        WalletBalanceDTO result = bybitAccountClient.getWalletBalance(1L);

        assertThat(result.getTotalEquity()).isNull();
        assertThat(result.getWalletBalance()).isNull();
        assertThat(result.getAvailableBalance()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: retCode가 0이 아니면 거래소 메시지를 담아 GET_WALLET_BALANCE_FAILED")
    void getWalletBalance_retCodeFailure() {
        givenKey(1L);
        GetWalletBalanceResponse failed = response("10003");
        failed.setRetMsg("Invalid API key");
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(failed));

        assertThatThrownBy(() -> bybitAccountClient.getWalletBalance(1L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.GET_WALLET_BALANCE_FAILED.getMessage() + ": Invalid API key");
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    @Test
    @DisplayName("getWalletBalance: 응답 본문이 없으면 GET_WALLET_BALANCE_FAILED")
    void getWalletBalance_nullBody() {
        givenKey(1L);
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> bybitAccountClient.getWalletBalance(1L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.GET_WALLET_BALANCE_FAILED.getMessage() + ": 응답 없음");
    }

    @Test
    @DisplayName("getWalletBalance: 등록된 키가 없으면 API_KEY_NOT_FOUND를 그대로 던지고 거래소를 호출하지 않는다")
    void getWalletBalance_keyNotFound() {
        given(exchangeKeyService.findKey(1L, ExchangeType.BYBIT))
                .willThrow(new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));

        assertThatThrownBy(() -> bybitAccountClient.getWalletBalance(1L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.API_KEY_NOT_FOUND.getMessage());
        then(accountClient).should(never()).getWalletBalance("UNIFIED");
    }

    // ─── validateApiKey ───

    @Test
    @DisplayName("validateApiKey: Bybit 키 검증기 결과를 그대로 반환한다")
    void validateApiKey_delegates() {
        given(bybitApiKeyValidator.validate("k", "s")).willReturn(true);
        given(bybitApiKeyValidator.validate("bad", "bad")).willReturn(false);

        assertThat(bybitAccountClient.validateApiKey("k", "s")).isTrue();
        assertThat(bybitAccountClient.validateApiKey("bad", "bad")).isFalse();
    }

    // ─── 헬퍼 ───

    private void givenKey(Long userId) {
        ExchangeKeyDTO key = ExchangeKeyDTO.builder()
                .userId(userId).exchangeType(ExchangeType.BYBIT)
                .apiKey("encKey").apiSecret("encSecret").build();
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt("encKey")).willReturn("rawKey");
        given(aesEncryptor.decrypt("encSecret")).willReturn("rawSecret");
    }

    private static GetWalletBalanceResponse response(String retCode, GetWalletBalanceResponse.Result.AccountInfo... accounts) {
        GetWalletBalanceResponse.Result result = new GetWalletBalanceResponse.Result();
        result.setList(List.of(accounts));
        GetWalletBalanceResponse response = new GetWalletBalanceResponse();
        response.setRetCode(retCode);
        response.setResult(result);
        return response;
    }

    private static GetWalletBalanceResponse.Result.AccountInfo account(String totalEquity,
                                                                      GetWalletBalanceResponse.Result.AccountInfo.CoinInfo... coins) {
        GetWalletBalanceResponse.Result.AccountInfo account = new GetWalletBalanceResponse.Result.AccountInfo();
        account.setAccountType("UNIFIED");
        account.setTotalEquity(totalEquity);
        account.setCoin(List.of(coins));
        return account;
    }

    private static GetWalletBalanceResponse.Result.AccountInfo.CoinInfo coin(String symbol, String walletBalance,
                                                                            String totalPositionIM, String totalOrderIM,
                                                                            String locked, String bonus) {
        GetWalletBalanceResponse.Result.AccountInfo.CoinInfo coin = new GetWalletBalanceResponse.Result.AccountInfo.CoinInfo();
        coin.setCoin(symbol);
        coin.setWalletBalance(walletBalance);
        coin.setTotalPositionIM(totalPositionIM);
        coin.setTotalOrderIM(totalOrderIM);
        coin.setLocked(locked);
        coin.setBonus(bonus);
        return coin;
    }
}
