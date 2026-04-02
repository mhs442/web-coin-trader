package com.coin.webcointrader.common.enums;

import lombok.Getter;

/**
 * 애플리케이션 전역 로그 메시지 Enum.
 * 로그 문자열을 한 곳에서 관리하여 일관성을 유지한다.
 */
@Getter
public enum LogMessage {

    // ─────────────────────────────────────────────
    // MarketService
    // ─────────────────────────────────────────────
    WS_TICKER_CALLBACK_REGISTERED("WebSocket 티커 콜백 등록 완료"),
    QTY_STEP_CACHE_INIT_SUCCESS("qtyStep 캐시 초기화 완료: {}개 종목"),
    QTY_STEP_CACHE_INIT_FAILED("qtyStep 캐시 초기화 실패: {}"),
    QTY_STEP_LOOKUP_FAILED("qtyStep 조회 실패: symbol={}"),
    QTY_CONVERT_ZERO("변환 결과 수량이 0: symbol={}, usdtAmount={}, currentPrice={}, qtyStep={}"),
    PRICE_LISTENER_ERROR("가격 리스너 오류: symbol={}, error={}"),

    // ─────────────────────────────────────────────
    // BybitWebSocketClient
    // ─────────────────────────────────────────────
    WS_SESSION_CLOSE_ERROR("WebSocket 세션 종료 중 오류: {}"),
    WS_SUBSCRIBE("WebSocket 구독 요청: {}"),
    WS_UNSUBSCRIBE("WebSocket 구독 해제 요청: {}"),
    WS_CONNECTED("Bybit WebSocket 연결 성공: {}"),
    WS_RECEIVED_DATA("WebSocket 수신 데이터 : {}"),
    WS_MESSAGE_PARSE_ERROR("WebSocket 메시지 파싱 오류: {}"),
    WS_CONNECTION_CLOSED("Bybit WebSocket 연결 종료: status={}"),
    WS_TRANSPORT_ERROR("Bybit WebSocket 전송 오류: {}"),
    WS_CONNECTING("Bybit WebSocket 연결 시도: {}"),
    WS_CONNECT_FAILED("Bybit WebSocket 연결 실패: {}"),
    WS_RECONNECT_SCHEDULED("Bybit WebSocket 재연결 예약: {}ms 후"),
    WS_RESUBSCRIBE_COMPLETE("WebSocket 재구독 완료: {}개 심볼"),
    WS_MESSAGE_SEND_FAILED("WebSocket 메시지 전송 실패: {}"),

    // ─────────────────────────────────────────────
    // AutoTradeService
    // ─────────────────────────────────────────────
    AUTO_TRADE_LISTENER_REGISTERED("자동매매 WebSocket 가격 리스너 등록 완료"),
    AUTO_TRADE_WS_ERROR("자동매매 WS 처리 오류: symbol={}, error={}"),
    AUTO_TRADE_STOPPED("자동매매 중지: userId={}, symbol={} (활성 큐 없음)"),
    AUTO_TRADE_SESSION_REFRESHED("자동매매 세션 갱신: userId={}, symbol={}, 큐 {}개"),
    AUTO_TRADE_STARTED("자동매매 시작: userId={}, symbol={}, 큐 {}개"),
    AUTO_TRADE_TICK_ERROR("자동매매 tick 오류: symbol={}, error={}"),
    MARGIN_MODE_SWITCH_FAILED("마진 모드 전환 실패 : queue={}, error={}"),
    LEVERAGE_SET_FAILED("레버리지 설정 실패 (계속 진행): {}"),
    POSITION_ENTRY_FAILED("포지션 진입 실패: queue={}, error={}"),
    INVESTMENT_HISTORY_SAVED("투자 히스토리 저장: symbol={}, side={}, profitLoss={}, mode={}"),
    QUEUE_DEACTIVATED("큐 비활성화: queue={}, reason={}"),

    // ─────────────────────────────────────────────
    // SimTradeService
    // ─────────────────────────────────────────────
    SIM_ORDER_EXECUTED("모의투자 주문 체결: userId={}, symbol={}, side={}, qty={}"),
    SIM_PROFIT_LOSS_APPLIED("모의투자 손익 반영: userId={}, amount={}, profitLoss={}, newBalance={}"),

    // ─────────────────────────────────────────────
    // ExceptionAdvice
    // ─────────────────────────────────────────────
    ERROR_OCCURRED("오류가 발생하였습니다 : {}"),

    // ─────────────────────────────────────────────
    // BybitFeignConfig
    // ─────────────────────────────────────────────
    BYBIT_API_CALL_DATA("[Bybit API 호출 데이터] = {} {} body={}");

    private final String message; // 로그 메시지 템플릿

    LogMessage(String message) {
        this.message = message;
    }
}
