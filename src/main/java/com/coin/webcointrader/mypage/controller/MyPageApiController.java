package com.coin.webcointrader.mypage.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.mypage.dto.*;
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
     * @param user    인증된 사용자 정보
     * @param request 검색조건을 담은 객체
     * @return 페이징된 패턴 응답
     */
    @GetMapping("/patterns")
    public PageResponse<MyPagePatternResponse> getPatterns(
            @AuthenticationPrincipal UserDTO user, MyPagePatternRequest request) {
        return myPageService.getPatterns(user.getId(), request);
    }

    /**
     * 사용자의 거래 히스토리를 페이징하여 조회한다.
     *
     * @param user    인증된 사용자 정보
     * @param request 검색조건을 담은 객체
     * @return 페이징된 거래 히스토리 응답
     */
    @GetMapping("/trades")
    public PageResponse<TradeHistoryResponse> getTrades(
            @AuthenticationPrincipal UserDTO user, TradeHistoryRequest request) {
        return myPageService.getTradeHistories(user.getId(), request);
    }

    /**
     * 사용자의 투자 히스토리를 페이징하여 조회한다.
     *
     * @param user    인증된 사용자 정보
     * @param request 검색조건을 담은 객체
     * @return 페이징된 투자 히스토리 응답
     */
    @GetMapping("/investments")
    public InvestmentHistoryPageResponse getInvestments(
            @AuthenticationPrincipal UserDTO user, InvestmentHistoryRequest request) {
        return myPageService.getInvestmentHistories(user.getId(), request);
    }
}
