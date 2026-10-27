package com.coin.webcointrader.common.trade.client;

import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;

import java.math.BigDecimal;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 거래소 거래 클라이언트 (API 명세).
 * 사용자 API 키로 주문·포지션 설정·체결·청산 손익을 처리하고, 사용자별 private 스트림을 관리한다.
 * 공통 처리는 {@link AbstractExchangeTradeClient}, 거래소별 차이는 그 하위 클래스가 구현한다.
 */
public interface ExchangeTradeClient {

    /** 이 클라이언트가 대상으로 하는 거래소 타입을 반환한다. */
    ExchangeType getExchangeType();

    /**
     * 시장가 주문(taker) 수수료율을 반환한다. (실수수료를 받지 못한 경우·모의 거래의 추정 수수료 계산용)
     *
     * @return 수수료율 (예: 0.055% → 0.00055)
     */
    BigDecimal getTakerFeeRate();

    /**
     * 주문을 실행한다.
     * 타임아웃 등으로 결과가 불명확하면 clientOrderId로 실제 접수 여부를 확인해 복구한다.
     *
     * @param request 주문 요청
     * @param userId  API 키를 조회할 사용자 ID
     * @return 주문 접수 결과 (거래소 주문 ID)
     * @throws com.coin.webcointrader.common.exception.CustomException 거래소가 주문을 거부한 경우 (ORDER_FAILED), 키 없음 (API_KEY_NOT_FOUND)
     */
    OrderResultDTO placeOrder(OrderRequestDTO request, Long userId);

    /**
     * 심볼의 마진 모드를 격리(Isolated)로 전환한다. 이미 격리 모드면 성공으로 처리한다.
     * (거래소마다 적용 범위가 다름 — Bybit: 계정 단위, Binance: 심볼 단위)
     *
     * @param userId API 키를 조회할 사용자 ID
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @throws com.coin.webcointrader.common.exception.CustomException 전환 실패 시 (SWITCH_MARGIN_MODE_FAILED)
     */
    void switchToIsolated(Long userId, String symbol);

    /**
     * 심볼 레버리지를 설정한다. (롱·숏 동일) 이미 같은 값이면 성공으로 처리한다.
     *
     * @param userId   API 키를 조회할 사용자 ID
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param leverage 레버리지 (예: "10")
     * @throws com.coin.webcointrader.common.exception.CustomException 설정 실패 시 (SET_LEVERAGE_FAILED)
     */
    void setLeverage(Long userId, String symbol, String leverage);

    /**
     * 주문 전송 직전에 private 스트림 체결 대기를 등록한다.
     *
     * @param userId        사용자 ID
     * @param clientOrderId 주문의 사용자 지정 ID
     * @return 스트림이 연결되어 대기 등록에 성공하면 true
     */
    boolean expectExecution(Long userId, String clientOrderId);

    /**
     * 체결 대기를 취소한다. (주문 실패 등) 등록되지 않았으면 무시한다.
     *
     * @param clientOrderId 주문의 사용자 지정 ID
     */
    void cancelExecution(String clientOrderId);

    /**
     * 주문의 실체결 정보를 스트림 우선으로 얻고, 실패 시 REST로 조회한다.
     *
     * @param orderId       거래소 주문 ID (REST 조회용)
     * @param clientOrderId 사용자 지정 주문 ID (스트림 매칭용)
     * @param symbol        종목 심볼
     * @param userId        사용자 ID
     * @return 집계된 체결 정보 (가중 평균가·수수료 합·수량 합), 조회 실패 시 null
     */
    ExecutionDTO getExecution(String orderId, String clientOrderId, String symbol, Long userId);

    /**
     * 청산 주문의 실현 손익을 조회한다.
     *
     * @param query  조회 조건 (청산 주문 ID·심볼, 직접 계산하는 거래소용 진입 시각·진입 수수료)
     * @param userId API 키를 조회할 사용자 ID
     * @return 청산 손익 (fundingFee 포함), 일치 건 없음·조회 실패 시 null
     */
    ClosedPnlDTO getClosedPnl(ClosedPnlQueryDTO query, Long userId);

    /**
     * 기준 시각 이후 심볼의 가장 최근 청산 건과 청산 방식을 찾는다. (놓친 외부 청산 재조정용)
     *
     * @param userId      API 키를 조회할 사용자 ID
     * @param symbol      종목 심볼
     * @param sinceMillis 검색 시작 시각 (epoch ms)
     * @return 청산 이벤트 (청산 건 없으면 orderId null·closeType OTHER), 조회 실패 시 null
     */
    PositionCloseDTO findLastClose(Long userId, String symbol, long sinceMillis);

    /**
     * 심볼의 현재 포지션 수량을 조회한다.
     *
     * @param userId API 키를 조회할 사용자 ID
     * @param symbol 종목 심볼
     * @return 포지션 수량 합계 (없으면 0), 조회 실패 시 null
     */
    BigDecimal getPositionSize(Long userId, String symbol);

    /**
     * private 스트림 연결 사용자를 주어진 집합과 같게 맞춘다.
     *
     * @param userIds 연결할 사용자 ID 집합
     */
    void syncPrivateStreams(Set<Long> userIds);

    /**
     * private 스트림에서 감지한 포지션 청산 리스너를 등록한다.
     *
     * @param listener 청산 이벤트 리스너
     */
    void addPositionCloseListener(Consumer<PositionCloseDTO> listener);
}
