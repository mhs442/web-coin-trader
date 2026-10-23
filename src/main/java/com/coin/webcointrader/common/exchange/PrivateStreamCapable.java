package com.coin.webcointrader.common.exchange;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 주문 체결 등 계정 전용 실시간 스트림(private stream)을 지원하는 거래소 어댑터.
 * 지원하지 않는 거래소는 구현하지 않으며, 호출부는 REST 조회 경로를 그대로 사용한다.
 */
public interface PrivateStreamCapable {

    /**
     * private 스트림 연결 대상 사용자 집합과 실제 연결을 동기화한다.
     * 대상인데 연결되지 않은 사용자는 연결을 열고, 대상에서 빠진 사용자는 연결을 닫는다.
     *
     * @param userIds 연결을 유지할 사용자 ID 집합 (활성 MAIN 세션 보유자)
     */
    void syncPrivateStreams(Set<Long> userIds);

    /**
     * 포지션 청산 체결 리스너를 등록한다.
     * 앱 주문·외부 청산(거래소 TP/SL, 강제청산, 수동 청산)을 가리지 않고 전달하므로 수신부에서 구분해야 한다.
     *
     * @param listener 청산 체결 수신 콜백 (WebSocket 수신 스레드에서 호출되므로 빠르게 반환해야 함)
     */
    void addPositionCloseListener(Consumer<PositionCloseDTO> listener);
}
