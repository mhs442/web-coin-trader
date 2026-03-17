package com.coin.webcointrader.autotrade.controller;

import com.coin.webcointrader.autotrade.dto.*;
import com.coin.webcointrader.autotrade.service.AutoTradeService;
import com.coin.webcointrader.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.*;
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
     * @param user   로그인 사용자
     * @return 패턴 큐 목록
     */
    @GetMapping("/patterns")
    public List<PatternQueueResponse> getPatterns(@RequestParam String symbol,
                                                  @AuthenticationPrincipal UserDTO user) {
        return patternQueueService.getQueues(user.getId(), symbol).stream()
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
     * @param user   로그인 사용자
     * @return 삭제 결과
     */
    @DeleteMapping("/patterns/{id}")
    public Map<String, String> deletePattern(@PathVariable Long id,
                                             @RequestParam String symbol,
                                             @AuthenticationPrincipal UserDTO user) {
        patternQueueService.deleteQueue(user.getId(), id);
        autoTradeService.syncSession(user.getId(), symbol);
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
        autoTradeService.syncSession(user.getId(), toggled.getSymbol());
        return toResponse(toggled);
    }

    /**
     * 자동매매 상태 조회
     *
     * @param symbol 코인 심볼
     * @param user   로그인 사용자
     * @return 자동매매 상태 응답
     */
    @GetMapping("/status")
    public AutoTradeStatusResponse getStatus(@RequestParam String symbol,
                                             @AuthenticationPrincipal UserDTO user) {
        return autoTradeService.getStatusResponse(user.getId(), symbol);
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
                .triggerSeconds(queue.getTriggerSeconds())
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
