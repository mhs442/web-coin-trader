package com.coin.webcointrader.mypage.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.mypage.dto.*;
import com.coin.webcointrader.mypage.service.MyPageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
     * 사용자의 투자 히스토리를 페이징하여 조회한다. (합산 통계 포함)
     *
     * @param user    인증된 사용자 정보
     * @param request 검색조건을 담은 객체
     * @return 페이징된 투자 히스토리 + 합산 통계 응답
     */
    @GetMapping("/investments")
    public InvestmentHistoryPageResponse getInvestments(
            @AuthenticationPrincipal UserDTO user, InvestmentHistoryRequest request) {
        return myPageService.getInvestmentHistories(user.getId(), request);
    }

    /**
     * 투자 1건의 연결된 거래 상세 내역을 조회한다.
     *
     * @param investmentId InvestmentHistory.id (투자 PK)
     * @param mode         거래 모드 ("main" 또는 "sim")
     * @param user         인증된 사용자 정보
     * @return 거래 상세 목록 (해당 사이클의 ENTRY + EXIT만)
     */
    @GetMapping("/investments/{investmentId}/trades")
    public List<InvestmentTradeDetailResponse> getInvestmentTradeDetails(
            @PathVariable Long investmentId,
            @RequestParam(defaultValue = "main") String mode,
            @AuthenticationPrincipal UserDTO user) {
        return myPageService.getInvestmentTradeDetails(user.getId(), investmentId, mode);
    }

    /**
     * 히스토리 차트 데이터를 조회한다. (일별 손익 / 심볼별 손익 / 승패 카운트)
     *
     * @param user    인증된 사용자 정보
     * @param request 검색조건을 담은 객체
     * @return 차트 데이터 응답
     */
    @GetMapping("/history/chart")
    public HistoryChartResponse getHistoryChart(
            @AuthenticationPrincipal UserDTO user, InvestmentHistoryRequest request) {
        return myPageService.getHistoryChart(user.getId(), request);
    }

    /**
     * 선택한 투자 히스토리를 삭제한다.
     *
     * @param request 삭제할 ID 목록과 거래 모드
     * @param user    인증된 사용자 정보
     * @return 처리 결과
     */
    @DeleteMapping("/investments")
    public Map<String, String> deleteInvestments(@RequestBody DeleteHistoryRequest request,
                                                 @AuthenticationPrincipal UserDTO user) {
        myPageService.deleteInvestmentHistories(user.getId(), request.getIds(), request.getMode());
        return Map.of("status", "ok");
    }

    /**
     * 전체 투자 히스토리를 삭제한다.
     *
     * @param mode 거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param user 인증된 사용자 정보
     * @return 처리 결과
     */
    @DeleteMapping("/investments/all")
    public Map<String, String> deleteAllInvestments(@RequestParam(defaultValue = "main") String mode,
                                                    @AuthenticationPrincipal UserDTO user) {
        myPageService.deleteAllInvestmentHistories(user.getId(), mode);
        return Map.of("status", "ok");
    }

    /**
     * 선택한 거래 히스토리를 삭제한다. (거래 내역 상세 삭제용)
     *
     * @param request 삭제할 ID 목록과 거래 모드
     * @param user    인증된 사용자 정보
     * @return 처리 결과
     */
    @DeleteMapping("/trades")
    public Map<String, String> deleteTrades(@RequestBody DeleteHistoryRequest request,
                                            @AuthenticationPrincipal UserDTO user) {
        myPageService.deleteTradeHistories(user.getId(), request.getIds(), request.getMode());
        return Map.of("status", "ok");
    }

    /**
     * 전체 거래 히스토리를 삭제한다.
     *
     * @param mode 거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param user 인증된 사용자 정보
     * @return 처리 결과
     */
    @DeleteMapping("/trades/all")
    public Map<String, String> deleteAllTrades(@RequestParam(defaultValue = "main") String mode,
                                               @AuthenticationPrincipal UserDTO user) {
        myPageService.deleteAllTradeHistories(user.getId(), mode);
        return Map.of("status", "ok");
    }
}
