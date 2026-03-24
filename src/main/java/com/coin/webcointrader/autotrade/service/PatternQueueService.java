package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 자동매매 패턴 큐 CRUD 서비스.
 * 큐의 조회, 추가, 삭제, 활성화 토글을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PatternQueueService {
    private final PatternQueueRepository patternQueueRepository;

    /**
     * 특정 사용자/심볼의 패턴 큐 목록을 생성일 오름차순으로 조회한다.
     *
     * @param userId 사용자 ID
     * @param symbol 코인 심볼
     * @return 패턴 큐 목록 (createdAt 오름차순)
     */
    public List<PatternQueue> getQueues(Long userId, String symbol) {
        return patternQueueRepository.findByUserIdAndSymbolOrderByCreatedAtAsc(userId, symbol);
    }

    /**
     * 새로운 패턴 큐를 검증 후 저장한다.
     * cascade에 의해 PatternStep, Pattern, PatternBlock이 함께 저장된다.
     * <ul>
     *   <li>트리거 시간 > 0, 트리거 비율 > 0</li>
     *   <li>단계는 1~20개</li>
     *   <li>패턴은 단계당 최대 2개</li>
     *   <li>조건 블록은 패턴당 최대 5개</li>
     *   <li>리프 블록은 필수 1개</li>
     *   <li>금액 > 0, 레버리지 > 0</li>
     *   <li>side는 LONG 또는 SHORT만 허용</li>
     *   <li>심볼당 큐 최대 20개</li>
     * </ul>
     *
     * @param userId  사용자 ID
     * @param request 패턴 큐 추가 요청
     * @return 저장된 PatternQueue 엔티티
     */
    @Transactional
    public PatternQueue addQueue(Long userId, AddPatternRequest request) {
        // 트리거 검증
        validateTrigger(request);
        // 단계/패턴/블록 검증
        validateSteps(request.getSteps());

        // 심볼당 최대 20개 제한
        long count = patternQueueRepository.countByUserIdAndSymbol(userId, request.getSymbol());
        if (count >= 20) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_QUEUES);
        }

        // PatternQueue 생성
        PatternQueue queue = new PatternQueue();
        queue.setUserId(userId);
        queue.setSymbol(request.getSymbol());
        queue.setTriggerRate(request.getTriggerRate());
        queue.setActive(false);

        // 단계 → 패턴 → 블록 계층 구조 생성
        for (AddPatternRequest.StepRequest stepReq : request.getSteps()) {
            PatternStep step = new PatternStep();
            step.setQueue(queue);
            step.setStepLevel(stepReq.getStepOrder());
            // 패턴이 2개면 가득 찬 상태
            step.setFull(stepReq.getPatterns().size() >= 2);

            int patternOrder = 1;
            for (AddPatternRequest.PatternRequest patternReq : stepReq.getPatterns()) {
                Pattern pattern = new Pattern();
                pattern.setStep(step);
                pattern.setAmount(patternReq.getAmount());
                pattern.setLeverage(patternReq.getLeverage());
                pattern.setStopLossRate(patternReq.getStopLossRate());
                pattern.setTakeProfitRate(patternReq.getTakeProfitRate());
                pattern.setPatternOrder(patternOrder++);

                // 조건 블록 추가
                for (AddPatternRequest.BlockRequest blockReq : patternReq.getConditionBlocks()) {
                    PatternBlock block = new PatternBlock();
                    block.setPattern(pattern);
                    block.setSide(Side.valueOf(blockReq.getSide().toUpperCase()));
                    block.setBlockOrder(blockReq.getBlockOrder());
                    block.setLeaf(false);
                    pattern.getBlocks().add(block);
                }

                // 리프 블록 추가
                AddPatternRequest.BlockRequest leafReq = patternReq.getLeafBlock();
                PatternBlock leafBlock = new PatternBlock();
                leafBlock.setPattern(pattern);
                leafBlock.setSide(Side.valueOf(leafReq.getSide().toUpperCase()));
                leafBlock.setBlockOrder(leafReq.getBlockOrder());
                leafBlock.setLeaf(true);
                pattern.getBlocks().add(leafBlock);

                step.getPatterns().add(pattern);
            }

            queue.getSteps().add(step);
        }

        // 모든 단계가 가득 찬 상태인지 확인
        boolean allFull = queue.getSteps().stream().allMatch(PatternStep::isFull);
        queue.setFull(allFull);

        return patternQueueRepository.save(queue);
    }

    /**
     * 패턴 큐를 삭제한다.
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 삭제할 큐 ID
     */
    @Transactional
    public void deleteQueue(Long userId, Long queueId) {
        PatternQueue queue = patternQueueRepository.findById(queueId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.QUEUE_NOT_FOUND));

        if (!queue.getUserId().equals(userId)) {
            throw new CustomException(ExceptionMessage.QUEUE_UNAUTHORIZED);
        }

        patternQueueRepository.delete(queue);
    }

    /**
     * 큐의 활성화 상태를 토글한다. (isActive: true ↔ false)
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 대상 큐 ID
     * @return 변경된 PatternQueue 엔티티
     */
    @Transactional
    public PatternQueue toggleActive(Long userId, Long queueId) {
        PatternQueue queue = patternQueueRepository.findById(queueId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.QUEUE_NOT_FOUND));

        if (!queue.getUserId().equals(userId)) {
            throw new CustomException(ExceptionMessage.QUEUE_UNAUTHORIZED);
        }

        queue.setActive(!queue.isActive());
        return patternQueueRepository.save(queue);
    }

    // ─────────────────────────────────────────────
    // 검증 메서드
    // ─────────────────────────────────────────────

    /**
     * 트리거 설정값 검증 (비율 > 0)
     */
    private void validateTrigger(AddPatternRequest request) {
        if (request.getTriggerRate() == null || request.getTriggerRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ExceptionMessage.INVALID_TRIGGER_RATE);
        }
    }

    /**
     * 단계/패턴/블록 구조 검증
     */
    private void validateSteps(List<AddPatternRequest.StepRequest> steps) {
        // 단계 1~20개 검증
        if (steps == null || steps.isEmpty()) {
            throw new CustomException(ExceptionMessage.EMPTY_STEPS);
        }
        if (steps.size() > 20) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_STEPS);
        }

        for (AddPatternRequest.StepRequest step : steps) {
            List<AddPatternRequest.PatternRequest> patterns = step.getPatterns();

            // 패턴 최대 2개 검증
            if (patterns == null || patterns.isEmpty()) {
                throw new CustomException(ExceptionMessage.EMPTY_PATTERNS);
            }
            if (patterns.size() > 2) {
                throw new CustomException(ExceptionMessage.EXCEED_MAX_PATTERNS);
            }

            for (AddPatternRequest.PatternRequest pattern : patterns) {
                validatePattern(pattern);
            }
        }
    }

    /**
     * 개별 패턴 검증 (금액, 레버리지, 블록 구조, side)
     */
    private void validatePattern(AddPatternRequest.PatternRequest pattern) {
        // 금액 검증
        if (pattern.getAmount() == null || pattern.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ExceptionMessage.INVALID_AMOUNT);
        }
        // 레버리지 검증
        if (pattern.getLeverage() == null || pattern.getLeverage() <= 0) {
            throw new CustomException(ExceptionMessage.INVALID_LEVERAGE);
        }
        // 리프 블록 필수
        if (pattern.getLeafBlock() == null) {
            throw new CustomException(ExceptionMessage.MISSING_LEAF_BLOCK);
        }
        // 조건 블록 최대 5개
        List<AddPatternRequest.BlockRequest> conditionBlocks = pattern.getConditionBlocks();
        if (conditionBlocks != null && conditionBlocks.size() > 5) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_CONDITION_BLOCKS);
        }

        // side 검증 (조건 블록)
        if (conditionBlocks != null) {
            for (AddPatternRequest.BlockRequest block : conditionBlocks) {
                validateSide(block.getSide());
            }
        }
        // side 검증 (리프 블록)
        validateSide(pattern.getLeafBlock().getSide());
    }

    /**
     * side 값 검증 (LONG 또는 SHORT만 허용)
     */
    private void validateSide(String side) {
        try {
            Side.valueOf(side.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ExceptionMessage.INVALID_SIDE);
        }
    }
}
