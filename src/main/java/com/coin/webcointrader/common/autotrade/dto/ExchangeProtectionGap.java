package com.coin.webcointrader.common.autotrade.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보유 포지션의 거래소 보호 주문(TP/SL)이 비어 있는 상태.
 * 큐가 꺼지면 앱 감시까지 멈추므로, 비어 있으면 큐 꺼짐 사유와 STOP 이력 맨 앞에 문구를 붙인다. (이력 길이 제한에 잘리지 않도록)
 */
@Getter
@RequiredArgsConstructor
public enum ExchangeProtectionGap {
    NONE(""),                                                                       // 비어 있지 않음 (또는 포지션 없음)
    TAKE_PROFIT("[거래소 익절 미설치 — 앱의 익절·손절도 멈춥니다] "),                  // TP만 처음부터 생략
    STOP_LOSS("[거래소 손절 미설치 — 앱의 익절·손절도 멈춥니다] "),                    // SL만 처음부터 생략
    ALL("[거래소 안전망 미설치 — 앱의 익절·손절도 멈춥니다] "),                        // 둘 다 생략, 또는 접수 실패
    CANCELLED("[거래소 안전망 취소됨 — 포지션이 남아 있으면 앱의 익절·손절도 멈춥니다] "); // 시장가 청산 접수로 취소

    private final String note; // 큐 꺼짐 사유와 STOP 이력 앞에 붙일 문구
}
