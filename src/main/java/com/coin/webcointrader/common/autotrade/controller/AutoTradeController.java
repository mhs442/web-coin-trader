package com.coin.webcointrader.common.autotrade.controller;

import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.autotrade.dto.*;
import com.coin.webcointrader.common.autotrade.service.AutoTradeService;
import com.coin.webcointrader.common.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.enums.Side;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 자동매매 REST API 컨트롤러.
 * 패턴 CRUD 및 자동매매 상태조회 엔드포인트를 제공한다.
 */
@RestController
@RequestMapping("/api/autotrade")
@RequiredArgsConstructor
public class AutoTradeController {
    private final PatternQueueService patternQueueService;
    private final AutoTradeService autoTradeService;

    /**
     * 큐 목록 조회
     *
     * @param symbol   코인 심볼
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (필수, 대소문자 무시 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @param user     로그인 사용자
     * @return 패턴 큐 목록
     */
    @GetMapping("/patterns")
    public List<PatternQueueResponse> getPatterns(@RequestParam String symbol,
                                                  @RequestParam(defaultValue = "main") String mode,
                                                  @RequestParam(required = false) String exchange,
                                                  @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = TradeMode.SIM.getMode().equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return patternQueueService.getQueues(user.getId(), ExchangeType.from(exchange), symbol, tradeMode).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 큐 추가
     *
     * @param request 패턴 큐 추가 요청
     * @param user    로그인 사용자
     * @return 저장된 패턴 큐 응답
     */
    @PostMapping("/patterns")
    public PatternQueueResponse addPattern(@RequestBody AddPatternRequest request,
                                           @AuthenticationPrincipal UserDTO user) {
        PatternQueue saved = patternQueueService.addQueue(user.getId(), request);
        return toResponse(saved);
    }

    /**
     * 큐 삭제 (삭제 후 자동매매 세션 동기화)
     *
     * @param id       큐 ID
     * @param symbol   코인 심볼
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (필수, 대소문자 무시 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @param user     로그인 사용자
     * @return 삭제 결과
     */
    @DeleteMapping("/patterns/{id}")
    public Map<String, String> deletePattern(@PathVariable Long id,
                                             @RequestParam String symbol,
                                             @RequestParam(defaultValue = "main") String mode,
                                             @RequestParam(required = false) String exchange,
                                             @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        ExchangeType exchangeType = ExchangeType.from(exchange);
        // [사용자 정책 2026-10-29] 삭제 전 보유 포지션 청산 (실패 시 예외 → 삭제하지 않고 큐 유지)
        autoTradeService.closeQueuePosition(user.getId(), id);
        patternQueueService.deleteQueue(user.getId(), id);
        // 삭제 후 해당 거래소 세션 동기화
        autoTradeService.syncSession(user.getId(), exchangeType, symbol, tradeMode);
        return Map.of("status", "ok");
    }

    /**
     * 큐 활성화/비활성화 토글 (토글 후 자동매매 세션 동기화)
     *
     * @param id   큐 ID
     * @param user 로그인 사용자
     * @return 변경된 패턴 큐 응답
     */
    @PatchMapping("/patterns/{id}/toggle")
    public PatternQueueResponse togglePattern(@PathVariable Long id,
                                              @AuthenticationPrincipal UserDTO user) {
        PatternQueue queue = patternQueueService.getQueue(user.getId(), id);
        boolean turnOn = !queue.isActive();
        // [사용자 정책 2026-10-29] 끄기 전 보유 포지션 청산 (실패 시 예외 → 끄지 않고 큐 유지)
        if (!turnOn) {
            autoTradeService.closeQueuePosition(user.getId(), id);
        }
        PatternQueue toggled = patternQueueService.setActive(user.getId(), id, turnOn);
        autoTradeService.syncSession(user.getId(), toggled.getExchangeType(), toggled.getSymbol(), toggled.getTradeMode());
        return toResponse(toggled);
    }

    /**
     * 전체 큐 목록 조회 (심볼 무관 - 큐 가져오기 모달용)
     *
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (대소문자 무시, 미전달 시 모든 거래소 — 조회 전용이라 기본 거래소 정책과 무관)
     * @param user     로그인 사용자
     * @return 전체 패턴 큐 목록
     */
    @GetMapping("/patterns/all")
    public List<PatternQueueResponse> getAllPatterns(@RequestParam(defaultValue = "main") String mode,
                                                     @RequestParam(required = false) String exchange,
                                                     @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        // 큐 가져오기: 거래소 미지정이면 전체 거래소 조회 (조회 전용 — 등록·복사·주문은 거래소 필수 유지)
        ExchangeType exchangeType = (exchange == null || exchange.isBlank()) ? null : ExchangeType.from(exchange);
        return patternQueueService.getAllQueues(user.getId(), exchangeType, tradeMode).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 큐 수정 (triggerRate + steps 교체, 수정 후 자동매매 세션 동기화)
     *
     * @param id      큐 ID
     * @param request 수정 요청 (triggerRate, steps)
     * @param user    로그인 사용자
     * @return 수정된 패턴 큐 응답
     */
    @PutMapping("/patterns/{id}")
    public PatternQueueResponse updatePattern(@PathVariable Long id,
                                              @RequestBody UpdatePatternRequest request,
                                              @AuthenticationPrincipal UserDTO user) {
        PatternQueue updated = patternQueueService.updateQueue(user.getId(), id, request);
        autoTradeService.syncSession(user.getId(), updated.getExchangeType(), updated.getSymbol(), updated.getTradeMode());
        return toResponse(updated);
    }

    /**
     * 큐 복사 (원본 큐 구조를 대상 심볼로 복제)
     *
     * @param queueId 복사할 원본 큐 ID
     * @param request 복사 요청 (대상 심볼, 거래 모드, 대상 거래소)
     * @param user    로그인 사용자
     * @return 복사된 패턴 큐 응답
     */
    @PostMapping("/patterns/{queueId}/copy")
    public PatternQueueResponse copyPattern(@PathVariable Long queueId,
                                            @RequestBody CopyQueueRequest request,
                                            @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(request.getMode()) ? TradeMode.SIM : TradeMode.MAIN;
        PatternQueue copied = patternQueueService.copyQueue(user.getId(), queueId, request.getSymbol(),
                request.getExchangeType(), tradeMode);
        return toResponse(copied);
    }

    /**
     * 자동매매 상태 조회
     *
     * @param symbol   코인 심볼
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (필수, 대소문자 무시 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @param user     로그인 사용자
     * @return 자동매매 상태 응답
     */
    @GetMapping("/status")
    public AutoTradeStatusResponse getStatus(@RequestParam String symbol,
                                             @RequestParam(defaultValue = "main") String mode,
                                             @RequestParam(required = false) String exchange,
                                             @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return autoTradeService.getStatusResponse(user.getId(), ExchangeType.from(exchange), symbol, tradeMode);
    }

    // ─────────────────────────────────────────────
    // PatternQueue → PatternQueueResponse 변환
    // ─────────────────────────────────────────────

    private PatternQueueResponse toResponse(PatternQueue queue) {
        // 패턴 순서는 엔티티 @OrderBy(stepLevel, patternOrder)를 그대로 따른다
        List<PatternQueueResponse.PatternResponse> patternResponses = queue.getPatterns().stream()
                .map(this::toPatternResponse)
                .toList();

        return PatternQueueResponse.builder()
                .id(queue.getId())
                .symbol(queue.getSymbol())
                .exchangeType(queue.getExchangeType().name())
                .active(queue.isActive())
                .cycle(queue.isCycle())
                .triggerRate(queue.getTriggerRate())
                .patterns(patternResponses)
                .build();
    }

    private PatternQueueResponse.PatternResponse toPatternResponse(Pattern pattern) {
        // 블록 목록의 마지막 원소가 리프 블록, 나머지가 조건 블록
        List<String> sides = pattern.getBlocks().stream().map(Side::name).toList();

        return PatternQueueResponse.PatternResponse.builder()
                .id(pattern.getId())
                .stepLevel(pattern.getStepLevel())
                .patternOrder(pattern.getPatternOrder())
                .amount(pattern.getAmount())
                .leverage(pattern.getLeverage())
                .stopLossRate(pattern.getStopLossRate())
                .takeProfitRate(pattern.getTakeProfitRate())
                .conditionBlocks(sides.subList(0, sides.size() - 1))
                .leafBlock(sides.get(sides.size() - 1))
                .build();
    }
}
