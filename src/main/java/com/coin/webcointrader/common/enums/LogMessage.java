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

    // ─────────────────────────────────────────────
    // BybitWebSocketClient
    // ─────────────────────────────────────────────
    WS_SESSION_CLOSE_ERROR("WebSocket 세션 종료 중 오류: {}"),
    WS_SUBSCRIBE("WebSocket 구독 요청: {}"),
    WS_UNSUBSCRIBE("WebSocket 구독 해제 요청: {}"),
    WS_CONNECTED("Bybit WebSocket 연결 성공: {}"),
    WS_MESSAGE_PARSE_ERROR("WebSocket 메시지 파싱 오류: {}"),
    WS_CONNECTION_CLOSED("Bybit WebSocket 연결 종료: status={}"),
    WS_TRANSPORT_ERROR("Bybit WebSocket 전송 오류: {}"),
    WS_CONNECTING("Bybit WebSocket 연결 시도: {}"),
    WS_CONNECT_FAILED("Bybit WebSocket 연결 실패: {}"),
    WS_RECONNECT_SCHEDULED("Bybit WebSocket 재연결 예약: {}ms 후"),
    WS_RESUBSCRIBE_COMPLETE("WebSocket 재구독 완료: {}개 심볼"),
    WS_MESSAGE_SEND_FAILED("WebSocket 메시지 전송 실패: {}"),

    // ─────────────────────────────────────────────
    // BybitPrivateWebSocketClient
    // ─────────────────────────────────────────────
    PRIVATE_WS_CONNECTING("Bybit Private WebSocket 연결 시도: userId={}"),
    PRIVATE_WS_CONNECT_FAILED("Bybit Private WebSocket 연결 실패: userId={}, error={}"),
    PRIVATE_WS_AUTH_SUCCESS("Bybit Private WebSocket 인증 성공: userId={}"),
    PRIVATE_WS_AUTH_FAILED("Bybit Private WebSocket 인증 실패 → 연결 종료(재연결 안 함): userId={}, reason={}"),
    PRIVATE_WS_SUBSCRIBE("Bybit Private WebSocket 구독 요청: userId={}, topics={}"),
    PRIVATE_WS_SUBSCRIBE_FAILED("Bybit Private WebSocket 구독 실패: userId={}, reason={}"),
    PRIVATE_WS_EXECUTION_RECEIVED("체결 수신: userId={}, symbol={}, orderLinkId={}, execType={}, execPrice={}, execQty={}, execFee={}"),
    PRIVATE_WS_CALLBACK_ERROR("체결 콜백 처리 오류: userId={}, execId={}, error={}"),
    PRIVATE_WS_CONNECTION_CLOSED("Bybit Private WebSocket 연결 종료: userId={}, status={}"),
    PRIVATE_WS_TRANSPORT_ERROR("Bybit Private WebSocket 전송 오류: userId={}, error={}"),
    PRIVATE_WS_RECONNECT_SCHEDULED("Bybit Private WebSocket 재연결 예약: userId={}, {}ms 후"),
    PRIVATE_WS_EXECUTION_WAIT_TIMEOUT("WS 체결 대기 시간 초과 → REST 체결 조회로 전환: orderLinkId={}, timeout={}ms"),
    PRIVATE_WS_OPEN_SKIPPED("Bybit Private WebSocket 연결 생략(REST 체결 조회로 동작): userId={}, reason={}"),
    BINANCE_PRIVATE_WS_OPEN_SKIPPED("Binance Private WebSocket 연결 생략(REST 체결 조회로 동작): userId={}, reason={}"),
    EXTERNAL_CLOSE_DETECTED("외부 청산 감지: queueId={}, symbol={}, closeType={}, orderId={}"),
    EXTERNAL_CLOSE_IGNORED("외부 청산 무시(거래소 포지션 남아 있음): userId={}, symbol={}, size={}, orderId={}"),
    CLOSE_ORDER_FAILED_ALREADY_CLOSED("앱 매도/청산 주문 실패 — 거래소 포지션 이미 청산됨: queueId={}, symbol={}, closeType={}, orderId={}"),
    EXTERNAL_CLOSE_RECONCILED("놓친 외부 청산 감지(재조정): userId={}, symbol={}, closeType={}, orderId={}"),
    EXTERNAL_CLOSE_CHECK_FAILED("외부 청산 확인 실패(포지션 조회 불가) → 상태 유지: userId={}, symbol={}, orderId={}"),

    // ─────────────────────────────────────────────
    // AutoTradeService
    // ─────────────────────────────────────────────
    AUTO_TRADE_LISTENER_REGISTERED("자동매매 WebSocket 가격 리스너 등록 완료"),
    AUTO_TRADE_WS_ERROR("자동매매 WS 처리 오류: symbol={}, error={}"),
    AUTO_TRADE_STOPPED("자동매매 중지: userId={}, symbol={} (활성 큐 없음)"),
    AUTO_TRADE_SESSION_REFRESHED("자동매매 세션 갱신: userId={}, symbol={}, 큐 {}개"),
    AUTO_TRADE_STARTED("자동매매 시작: userId={}, symbol={}, 큐 {}개"),
    MARGIN_MODE_SWITCH_FAILED("마진 모드 전환 실패 : queue={}, error={}"),
    LEVERAGE_SET_FAILED("레버리지 설정 실패 : queue={}, error={}"),
    POSITION_ENTRY_FAILED("포지션 진입 실패: queue={}, error={}"),
    INVESTMENT_HISTORY_SAVED("투자 히스토리 저장: symbol={}, side={}, profitLoss={}, mode={}"),
    QUEUE_DEACTIVATED("큐 비활성화: queue={}, reason={}"),
    TP_LIMIT_FILLED("익절 지정가 체결 → 1단계 리셋: queue={}, symbol={}, orderId={}"),
    TP_CANCEL_FAILED("손절 전 익절 지정가 취소 미확인 → 손절 보류: queue={}, reason={}, 연속={}"),
    TP_LIMIT_ORDER_FAILED("익절 지정가 등록 실패 → 시장가 청산 후 큐 비활성화: queue={}, reason={}"),
    ENTRY_NOT_CONFIRMED("진입 주문 후 거래소 포지션 미확인: queue={}, positionSize={}, 연속={}"),
    CLOSE_NOT_CONFIRMED("청산 주문 후 거래소 포지션 0 미확인: queue={}, positionSize={}, 연속={}"),
    CLOSE_QTY_SKIP_DEACTIVATED("포지션 청산 수량 오류 5회 초과 → 큐 비활성화: queue={}, phase={}"),
    ENTRY_SKIP_LIMIT_EXCEEDED("최소 주문 단위 미달 5회 초과 → 큐 비활성화 (amount × leverage 부족): queue={}, symbol={}, notional={}, currentPrice={}"),

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
    BYBIT_API_CALL_DATA("[Bybit API 호출 데이터] = {} {} body={}"),
    BINANCE_API_CALL("[Binance API 호출] = {} {}"),  // 메서드·경로만 (쿼리에 서명 포함)
    BINANCE_WEIGHT_HIGH("[Binance 사용 가중치 경고] 최근 1분 {}/{} - {} {}"),  // 메서드·경로만

    // ─────────────────────────────────────────────
    // BinanceWebSocketClient
    // ─────────────────────────────────────────────
    BINANCE_WS_CONNECTING("Binance WebSocket 연결 시도: {}"),
    BINANCE_WS_CONNECTED("Binance WebSocket 연결 성공: {}"),
    BINANCE_WS_CONNECT_FAILED("Binance WebSocket 연결 실패: {}"),
    BINANCE_WS_CONNECTION_CLOSED("Binance WebSocket 연결 종료: status={}"),
    BINANCE_WS_TRANSPORT_ERROR("Binance WebSocket 전송 오류: {}"),
    BINANCE_WS_RECONNECT_SCHEDULED("Binance WebSocket 재연결 예약: {}ms 후"),
    BINANCE_WS_SUBSCRIPTION_SENT("Binance WebSocket {} 요청: 스트림 {}개"),
    BINANCE_WS_MESSAGE_PARSE_ERROR("Binance WebSocket 메시지 파싱 실패: {}"),
    BINANCE_WS_MESSAGE_SEND_FAILED("Binance WebSocket 메시지 전송 실패: {}"),
    BINANCE_WS_SESSION_CLOSE_ERROR("Binance WebSocket 세션 종료 중 오류: {}"),

    // ─────────────────────────────────────────────
    // BinancePrivateWebSocketClient (user data stream)
    // ─────────────────────────────────────────────
    BINANCE_PRIVATE_WS_CONNECTING("Binance Private WebSocket 연결 시도(listenKey 발급): userId={}"),
    BINANCE_PRIVATE_WS_CONNECTED("Binance Private WebSocket 연결 성공: userId={}"),
    BINANCE_PRIVATE_WS_CONNECT_FAILED("Binance Private WebSocket 연결 실패: userId={}, error={}"),
    BINANCE_PRIVATE_WS_LISTEN_KEY_FAILED("Binance listenKey 발급 실패 → 재시도: userId={}, error={}"),
    BINANCE_PRIVATE_WS_INVALID_KEY("Binance API 키 거부 → 연결 종료(재연결 안 함): userId={}, reason={}"),
    BINANCE_PRIVATE_WS_KEEPALIVE_FAILED("Binance listenKey 연장 실패: userId={}, error={}"),
    BINANCE_PRIVATE_WS_LISTEN_KEY_EXPIRED("Binance listenKey 만료 → 재발급·재연결: userId={}"),
    BINANCE_PRIVATE_WS_CLOSE_LISTEN_KEY_FAILED("Binance listenKey 폐기 실패(60분 후 자동 만료): userId={}, error={}"),
    BINANCE_PRIVATE_WS_ORDER_UPDATE_RECEIVED("주문 업데이트 수신: userId={}, symbol={}, clientOrderId={}, execType={}, status={}, lastPrice={}, lastQty={}, fee={} {}"),
    BINANCE_PRIVATE_WS_CALLBACK_ERROR("주문 업데이트 콜백 처리 오류: userId={}, clientOrderId={}, error={}"),
    BINANCE_PRIVATE_WS_CONNECTION_CLOSED("Binance Private WebSocket 연결 종료: userId={}, status={}"),
    BINANCE_PRIVATE_WS_TRANSPORT_ERROR("Binance Private WebSocket 전송 오류: userId={}, error={}"),
    BINANCE_PRIVATE_WS_RECONNECT_SCHEDULED("Binance Private WebSocket 재연결 예약: userId={}, {}ms 후"),
    BINANCE_PRIVATE_WS_EXECUTION_WAIT_TIMEOUT("Binance WS 체결 대기 시간 초과 → REST 체결 조회로 전환: clientOrderId={}, timeout={}ms"),
    BINANCE_PRIVATE_WS_ORDER_ENDED_WITHOUT_FILL("Binance 주문이 체결 없이 종료됨 → REST 체결 조회로 확인: clientOrderId={}, status={}");

    private final String message; // 로그 메시지 템플릿

    LogMessage(String message) {
        this.message = message;
    }
}
