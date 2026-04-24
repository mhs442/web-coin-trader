package com.coin.webcointrader.autotrade.controller;

import com.coin.webcointrader.autotrade.dto.*;
import com.coin.webcointrader.autotrade.service.AutoTradeService;
import com.coin.webcointrader.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.entity.Pattern;
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
     * @param symbol 코인 심볼
     * @param mode   거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param user   로그인 사용자
     * @return 패턴 큐 목록
     */
    @GetMapping("/patterns")
    public List<PatternQueueResponse> getPatterns(@RequestParam String symbol,
                                                  @RequestParam(defaultValue = "main") String mode,
                                                  @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = TradeMode.SIM.getMode().equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return patternQueueService.getQueues(user.getId(), symbol, tradeMode).stream()
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
     * @param id     큐 ID
     * @param symbol 코인 심볼
     * @param mode   거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param user   로그인 사용자
     * @return 삭제 결과
     */
    @DeleteMapping("/patterns/{id}")
    public Map<String, String> deletePattern(@PathVariable Long id,
                                             @RequestParam String symbol,
                                             @RequestParam(defaultValue = "main") String mode,
                                             @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        patternQueueService.deleteQueue(user.getId(), id);
        autoTradeService.syncSession(user.getId(), symbol, tradeMode);
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
        PatternQueue toggled = patternQueueService.toggleActive(user.getId(), id);
        autoTradeService.syncSession(user.getId(), toggled.getSymbol(), toggled.getTradeMode());
        return toResponse(toggled);
    }

    /**
     * 전체 큐 목록 조회 (심볼 무관 - 큐 가져오기 모달용)
     *
     * @param mode 거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param user 로그인 사용자
     * @return 전체 패턴 큐 목록
     */
    @GetMapping("/patterns/all")
    public List<PatternQueueResponse> getAllPatterns(@RequestParam(defaultValue = "main") String mode,
                                                     @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return patternQueueService.getAllQueues(user.getId(), tradeMode).stream()
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
        autoTradeService.syncSession(user.getId(), updated.getSymbol(), updated.getTradeMode());
        return toResponse(updated);
    }

    /**
     * 큐 복사 (원본 큐 구조를 대상 심볼로 복제)
     *
     * @param queueId 복사할 원본 큐 ID
     * @param request 복사 요청 (대상 심볼, 거래 모드)
     * @param user    로그인 사용자
     * @return 복사된 패턴 큐 응답
     */
    @PostMapping("/patterns/{queueId}/copy")
    public PatternQueueResponse copyPattern(@PathVariable Long queueId,
                                            @RequestBody CopyQueueRequest request,
                                            @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(request.getMode()) ? TradeMode.SIM : TradeMode.MAIN;
        PatternQueue copied = patternQueueService.copyQueue(user.getId(), queueId, request.getSymbol(), tradeMode);
        return toResponse(copied);
    }

    /**
     * 자동매매 상태 조회
     *
     * @param symbol 코인 심볼
     * @param mode   거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param user   로그인 사용자
     * @return 자동매매 상태 응답
     */
    @GetMapping("/status")
    public AutoTradeStatusResponse getStatus(@RequestParam String symbol,
                                             @RequestParam(defaultValue = "main") String mode,
                                             @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return autoTradeService.getStatusResponse(user.getId(), symbol, tradeMode);
    }

    // ─────────────────────────────────────────────
    // PatternQueue → PatternQueueResponse 변환
    // ─────────────────────────────────────────────

    private PatternQueueResponse toResponse(PatternQueue queue) {
        List<PatternQueueResponse.StepResponse> stepResponses = queue.getSteps().stream()
                .map(this::toStepResponse)
                .toList();

        return PatternQueueResponse.builder()
                .id(queue.getId())
                .symbol(queue.getSymbol())
                .active(queue.isActive())
                .triggerRate(queue.getTriggerRate())
                .steps(stepResponses)
                .build();
    }

    private PatternQueueResponse.StepResponse toStepResponse(PatternStep step) {
        List<PatternQueueResponse.PatternResponse> patternResponses = step.getPatterns().stream()
                .map(this::toPatternResponse)
                .toList();

        return PatternQueueResponse.StepResponse.builder()
                .id(step.getId())
                .stepLevel(step.getStepLevel())
                .patterns(patternResponses)
                .build();
    }

    private PatternQueueResponse.PatternResponse toPatternResponse(Pattern pattern) {
        List<PatternQueueResponse.BlockResponse> blockResponses = pattern.getBlocks().stream()
                .map(this::toBlockResponse)
                .toList();

        return PatternQueueResponse.PatternResponse.builder()
                .id(pattern.getId())
                .patternOrder(pattern.getPatternOrder())
                .amount(pattern.getAmount())
                .leverage(pattern.getLeverage())
                .stopLossRate(pattern.getStopLossRate())
                .takeProfitRate(pattern.getTakeProfitRate())
                .blocks(blockResponses)
                .build();
    }

    private PatternQueueResponse.BlockResponse toBlockResponse(PatternBlock block) {
        return PatternQueueResponse.BlockResponse.builder()
                .id(block.getId())
                .side(block.getSide().name())
                .blockOrder(block.getBlockOrder())
                .isLeaf(block.isLeaf())
                .build();
    }
}
