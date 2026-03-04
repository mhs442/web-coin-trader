package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.QueueStep;
import com.coin.webcointrader.common.entity.Side;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 자동매매 패턴 큐 CRUD 서비스.
 * 큐의 조회, 추가, 삭제, 활성화 토글을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PatternQueueService {
    private final QueueRepository queueRepository;

    /**
     * 특정 사용자/심볼의 큐 목록을 정렬 순서대로 조회한다. (삭제되지 않은 것만)
     *
     * @param userId 사용자 ID
     * @param symbol 코인 심볼
     * @return 큐 목록 (sortOrder 오름차순)
     */
    public List<Queue> getQueues(Long userId, String symbol) {
        return queueRepository.findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc(userId, symbol, "N");
    }

    /**
     * 새로운 큐를 검증 후 저장한다.
     * <ul>
     *   <li>단계는 1개 이상이어야 함</li>
     *   <li>side는 LONG 또는 SHORT만 허용</li>
     *   <li>수량은 0보다 커야 함</li>
     *   <li>심볼당 최대 20개 제한</li>
     * </ul>
     *
     * @param userId  사용자 ID
     * @param request 패턴 추가 요청
     * @return 저장된 Queue 엔티티
     */
    @Transactional
    public Queue addQueue(Long userId, AddPatternRequest request) {
        List<AddPatternRequest.StepRequest> steps = request.getSteps();

        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("단계를 1개 이상 입력해야 합니다.");
        }

        // side 검증
        for (AddPatternRequest.StepRequest step : steps) {
            try {
                Side.valueOf(step.getSide().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("방향은 LONG 또는 SHORT만 입력할 수 있습니다.");
            }

            BigDecimal qty = new BigDecimal(step.getQuantity());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("수량은 0보다 커야 합니다.");
            }
        }

        // 최대 20개 제한
        long count = queueRepository.countByUserIdAndSymbolAndDelYn(userId, request.getSymbol(), "N");
        if (count >= 20) {
            throw new IllegalArgumentException("패턴은 최대 20개까지 등록할 수 있습니다.");
        }

        Queue queue = new Queue();
        queue.setUserId(userId);
        queue.setSymbol(request.getSymbol());
        queue.setSortOrder((int) count);
        queue.setUseYn("Y");
        queue.setDelYn("N");
        queue.setCreatedAt(LocalDateTime.now());

        for (int i = 0; i < steps.size(); i++) {
            AddPatternRequest.StepRequest stepReq = steps.get(i);

            QueueStep queueStep = new QueueStep();
            queueStep.setQueue(queue);
            queueStep.setStepOrder(i);
            queueStep.setSide(Side.valueOf(stepReq.getSide().toUpperCase()));
            queueStep.setQuantity(new BigDecimal(stepReq.getQuantity()));

            queue.getSteps().add(queueStep);
        }

        return queueRepository.save(queue);
    }

    /**
     * 큐를 소프트 삭제하고, 남은 큐들의 sortOrder를 재정렬한다.
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 삭제할 큐 ID
     */
    @Transactional
    public void deleteQueue(Long userId, Long queueId) {
        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("큐를 찾을 수 없습니다."));

        if (!queue.getUserId().equals(userId)) {
            throw new IllegalArgumentException("삭제 권한이 없습니다.");
        }

        queue.setDelYn("Y");
        queue.setDeletedAt(LocalDateTime.now());
        queueRepository.save(queue);

        // sortOrder 재정렬 (삭제되지 않은 것만)
        List<Queue> remaining = queueRepository
                .findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc(userId, queue.getSymbol(), "N");
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setSortOrder(i);
        }
        queueRepository.saveAll(remaining);
    }

    /**
     * 큐의 활성화 상태를 토글한다. (use_yn: Y ↔ N)
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 대상 큐 ID
     * @return 변경된 Queue 엔티티
     */
    @Transactional
    public Queue toggleActive(Long userId, Long queueId) {
        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("큐를 찾을 수 없습니다."));

        if (!queue.getUserId().equals(userId)) {
            throw new IllegalArgumentException("권한이 없습니다.");
        }

        queue.setUseYn("Y".equals(queue.getUseYn()) ? "N" : "Y");
        return queueRepository.save(queue);
    }
}
