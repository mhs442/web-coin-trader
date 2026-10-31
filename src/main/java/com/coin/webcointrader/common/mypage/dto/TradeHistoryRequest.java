package com.coin.webcointrader.common.mypage.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 마이페이지 실패·중지 기록 조회 요청. (히스토리 탭 공통 필터와 같은 형태)
 */
@Getter @Setter
public class TradeHistoryRequest {
    private String symbol;          // 심볼 키워드 (부분일치, 없으면 전체)
    // 기본 1주일 전부터
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startDate = LocalDateTime.now().minusDays(7).toLocalDate().atStartOfDay();
    // 현재시간까지
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endDate = LocalDateTime.now();
    private String sort = "desc";   // 정렬 방향 ("asc" 또는 그 외 = 최신순)
    private int page = 0;           // 페이지 번호 (0부터)
    private int size = 20;          // 페이지 크기
    private String mode = "main";   // 거래 모드 ("main" 또는 "sim")
    private String exchange;        // 거래소 (헤더 선택값, 필수)
}
