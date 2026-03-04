package com.coin.webcointrader.mypage.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.mypage.dto.MyPagePatternResponse;
import com.coin.webcointrader.mypage.dto.TradeHistoryResponse;
import com.coin.webcointrader.mypage.service.MyPageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mypage")
@RequiredArgsConstructor
public class MyPageApiController {
    private final MyPageService myPageService;

    /**
     * 사용자의 패턴(큐) 목록을 페이징하여 조회한다.
     *
     * @param user      인증된 사용자 정보
     * @param symbol    심볼 검색 키워드
     * @param startDate 조회 시작일
     * @param endDate   조회 종료일
     * @param sort      정렬 방향
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 페이징된 패턴 응답
     */
    @GetMapping("/patterns")
    public PageResponse<MyPagePatternResponse> getPatterns(
            @AuthenticationPrincipal UserDTO user,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return myPageService.getPatterns(user.getId(), symbol, startDate, endDate, sort, page, size);
    }

    /**
     * 사용자의 거래 히스토리를 페이징하여 조회한다.
     *
     * @param user      인증된 사용자 정보
     * @param symbol    심볼 검색 키워드
     * @param startDate 조회 시작일
     * @param endDate   조회 종료일
     * @param sort      정렬 방향
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 페이징된 거래 히스토리 응답
     */
    @GetMapping("/trades")
    public PageResponse<TradeHistoryResponse> getTrades(
            @AuthenticationPrincipal UserDTO user,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return myPageService.getTradeHistories(user.getId(), symbol, startDate, endDate, sort, page, size);
    }
}
