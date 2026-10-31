package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.common.autotrade.dto.PatternQueueDTO;
import com.coin.webcointrader.common.autotrade.dto.UpdatePatternRequest;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.autotrade.util.TradeCalculator;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.market.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 자동매매 패턴 큐 CRUD 서비스.
 * 큐의 조회, 추가, 삭제, 활성화 토글을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PatternQueueService {
    private final PatternQueueRepository patternQueueRepository;
    private final MarketService marketService;

    /**
     * 특정 사용자/거래소/심볼/거래모드의 패턴 큐 목록을 생성일 오름차순으로 조회한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @return 패턴 큐 목록 (createdAt 오름차순)
     */
    public List<PatternQueue> getQueues(Long userId, ExchangeType exchangeType, String symbol, TradeMode tradeMode) {
        return patternQueueRepository.findByUserIdAndSymbolAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, tradeMode, exchangeType);
    }

    /**
     * 사용자의 패턴 큐를 거래소/기간/심볼 조건으로 페이징 조회하여 DTO 로 반환한다. (마이페이지 등 타 도메인용)
     * 계층(단계→패턴→블록)은 지연 로딩이므로 트랜잭션 안에서 DTO 로 변환한다.
     *
     * @param userId       사용자 ID
     * @param symbol       심볼 키워드 (null/공백이면 심볼 필터 없음, 있으면 대소문자 무시 부분일치)
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입 (해당 거래소 큐만 조회)
     * @param start        조회 시작 일시
     * @param end          조회 종료 일시
     * @param pageable     페이징/정렬 조건
     * @return 패턴 큐 DTO 페이지
     */
    @Transactional(readOnly = true)
    public Page<PatternQueueDTO> findQueuePage(Long userId, String symbol, TradeMode tradeMode, ExchangeType exchangeType,
                                               LocalDateTime start, LocalDateTime end, Pageable pageable) {
        Page<PatternQueue> page;
        if (symbol != null && !symbol.isBlank()) {
            // 심볼 키워드 있으면 DB 레벨 LIKE 필터로 페이징
            page = patternQueueRepository.findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                    userId, symbol, tradeMode, exchangeType, start, end, pageable);
        } else {
            // 심볼 키워드 없으면 날짜 범위만으로 DB 페이징
            page = patternQueueRepository.findByUserIdAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                    userId, tradeMode, exchangeType, start, end, pageable);
        }
        return page.map(this::toDto);
    }

    /**
     * PatternQueue 엔티티를 평평한 패턴 목록 포함 DTO 로 변환한다.
     * 패턴 순서는 엔티티 @OrderBy(stepLevel, patternOrder)를 그대로 따른다.
     *
     * @param queue 패턴 큐 엔티티
     * @return 패턴 큐 DTO
     */
    private PatternQueueDTO toDto(PatternQueue queue) {
        List<PatternQueueDTO.PatternDTO> patterns = queue.getPatterns().stream()
                .map(p -> {
                    // 블록 목록의 마지막 원소가 리프 블록, 나머지가 조건 블록
                    List<Side> blocks = p.getBlocks();
                    return PatternQueueDTO.PatternDTO.builder()
                            .stepLevel(p.getStepLevel())
                            .patternOrder(p.getPatternOrder())
                            .amount(p.getAmount())
                            .leverage(p.getLeverage())
                            .stopLossRate(p.getStopLossRate())
                            .takeProfitRate(p.getTakeProfitRate())
                            .conditionBlocks(List.copyOf(blocks.subList(0, blocks.size() - 1)))
                            .leafBlock(blocks.get(blocks.size() - 1))
                            .build();
                })
                .toList();

        return PatternQueueDTO.builder()
                .id(queue.getId())
                .symbol(queue.getSymbol())
                .exchangeType(queue.getExchangeType().name())
                .active(queue.isActive())
                .triggerRate(queue.getTriggerRate())
                .createdAt(queue.getCreatedAt())
                .patterns(patterns)
                .build();
    }

    /**
     * 새로운 패턴 큐를 검증 후 저장한다.
     * cascade에 의해 Pattern(블록 목록 포함)이 함께 저장된다.
     * <ul>
     *   <li>트리거 비율 > 0</li>
     *   <li>패턴마다 단계 번호(stepLevel) 1 이상 필수, 단계 번호는 1부터 빠짐없이 연속</li>
     *   <li>단계는 1~20개</li>
     *   <li>패턴은 단계당 정확히 2개 (LONG 시작 1개 + SHORT 시작 1개)</li>
     *   <li>조건 블록은 패턴당 1~5개</li>
     *   <li>리프 블록은 필수 1개</li>
     *   <li>금액 > 0, 레버리지 > 0</li>
     *   <li>side는 LONG 또는 SHORT만 허용</li>
     *   <li>거래소·심볼·거래 모드당 큐 1개</li>
     *   <li>거래소는 필수, 지원 목록(ExchangeType)만 허용</li>
     * </ul>
     *
     * @param userId  사용자 ID
     * @param request 패턴 큐 추가 요청
     * @return 저장된 PatternQueue 엔티티
     * @throws CustomException 거래소 미전달(EXCHANGE_TYPE_REQUIRED), 지원하지 않는 거래소(INVALID_EXCHANGE_TYPE),
     *                         거래소에서 거래할 수 없는 종목(SYMBOL_NOT_TRADABLE), 이미 큐가 있음(EXCEED_MAX_QUEUES)
     */
    @Transactional
    public PatternQueue addQueue(Long userId, AddPatternRequest request) {
        // 트리거 검증
        validateTrigger(request.getTriggerRate());
        // 패턴/블록 검증
        validatePatterns(request.getPatterns());

        // 거래소 타입 변환 (필수 — 미전달이면 EXCHANGE_TYPE_REQUIRED, 지원하지 않는 값이면 INVALID_EXCHANGE_TYPE)
        ExchangeType exchangeType = ExchangeType.from(request.getExchangeType());
        validateTradable(exchangeType, request.getSymbol());

        // 심볼당 1개 제한 (코드 레벨 강제)
        // 거래 모드 + 거래소별 큐 개수 검증
        TradeMode mode = "SIM".equalsIgnoreCase(request.getTradeMode()) ? TradeMode.SIM : TradeMode.MAIN;
        long count = patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(
                userId, request.getSymbol(), mode, exchangeType);
        if (count >= 1) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_QUEUES);
        }

        // PatternQueue 생성
        PatternQueue queue = new PatternQueue();
        queue.setUserId(userId);
        queue.setSymbol(request.getSymbol());
        queue.setTriggerRate(request.getTriggerRate());
        queue.setActive(false);
        queue.setCycle(request.isCycle());
        queue.setVolumeStopEnabled(request.isVolumeStopEnabled());
        // 거래 모드 설정 (위에서 판단한 값, 없으면 MAIN)
        queue.setTradeMode(mode);
        // 거래소 타입 설정 (프론트에서 전달한 값, 위에서 검증 완료)
        queue.setExchangeType(exchangeType);

        // 패턴(블록 목록 포함) 생성
        buildPatterns(queue, request.getPatterns());

        // 검증 통과 = 모든 단계에 패턴이 정확히 2개 → 항상 가득 찬 상태
        queue.setFull(true);

        return saveNewQueue(queue);
    }

    /**
     * 큐를 삭제할 수 있는지 검사한다. (권한 확인 + 활성 큐 삭제 금지)
     * 삭제 전 포지션 청산보다 먼저 호출해, 활성 큐에 대해 청산만 되고 삭제는 거부되는 일이 없게 한다.
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 삭제할 큐 ID
     * @return 삭제 가능한 큐 엔티티
     * @throws CustomException 큐 없음(QUEUE_NOT_FOUND), 타인 큐(QUEUE_UNAUTHORIZED), 활성 큐(QUEUE_ACTIVE_CANNOT_DELETE)
     */
    public PatternQueue validateDeletable(Long userId, Long queueId) {
        PatternQueue queue = getQueue(userId, queueId);
        // [사용자 정책 2026-10-30] 활성(ON) 큐는 삭제 불가 — 먼저 비활성화해야 삭제 가능
        if (queue.isActive()) {
            throw new CustomException(ExceptionMessage.QUEUE_ACTIVE_CANNOT_DELETE);
        }
        return queue;
    }

    /**
     * 패턴 큐를 삭제한다. (활성 큐는 삭제하지 않음)
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 삭제할 큐 ID
     * @throws CustomException 큐 없음, 타인 큐, 활성 큐(QUEUE_ACTIVE_CANNOT_DELETE)
     */
    @Transactional
    public void deleteQueue(Long userId, Long queueId) {
        patternQueueRepository.delete(validateDeletable(userId, queueId));
    }

    /**
     * 사용자의 큐를 조회한다. (권한 확인 포함)
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 대상 큐 ID
     * @return PatternQueue 엔티티
     */
    public PatternQueue getQueue(Long userId, Long queueId) {
        PatternQueue queue = patternQueueRepository.findById(queueId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.QUEUE_NOT_FOUND));

        if (!queue.getUserId().equals(userId)) {
            throw new CustomException(ExceptionMessage.QUEUE_UNAUTHORIZED);
        }
        return queue;
    }

    /**
     * 큐의 활성화 상태를 지정한 값으로 변경한다.
     * 끄기 전 포지션 청산 중 엔진이 큐를 먼저 비활성화해도 다시 켜지지 않도록 반전이 아닌 목표 상태로 지정한다.
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 대상 큐 ID
     * @param active  변경할 활성 상태
     * @return 변경된 PatternQueue 엔티티
     */
    @Transactional
    public PatternQueue setActive(Long userId, Long queueId, boolean active) {
        PatternQueue queue = getQueue(userId, queueId);
        queue.setActive(active);
        // [사용자 정책 2026-10-29] 사용자가 직접 끄거나 켜면 거래량 급변 중지 예약은 해제 (STOP 이력 없음)
        queue.setVolumeStopReason(null);
        return patternQueueRepository.save(queue);
    }

    /**
     * 사용자/거래소/거래모드의 전체 패턴 큐 목록을 심볼 무관하게 조회한다. (큐 가져오기 모달용)
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입 (null 이면 모든 거래소)
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @return 전체 패턴 큐 목록 (createdAt 오름차순)
     */
    public List<PatternQueue> getAllQueues(Long userId, ExchangeType exchangeType, TradeMode tradeMode) {
        // 거래소 미지정 → 전체 거래소 큐 (큐 가져오기: 다른 거래소 큐도 복사 원본으로 사용)
        if (exchangeType == null) {
            return patternQueueRepository.findByUserIdAndTradeModeOrderByCreatedAtAsc(userId, tradeMode);
        }
        return patternQueueRepository.findByUserIdAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, tradeMode, exchangeType);
    }

    /**
     * 기존 큐의 구조(단계/패턴/블록)를 대상 심볼로 딥 카피하여 저장한다.
     * 원본 큐의 트리거 비율, 단계, 패턴, 블록을 그대로 복제하되 심볼(필요 시 거래소)만 변경한다.
     * 복사된 큐는 비활성 상태로 생성된다.
     *
     * @param userId         사용자 ID (권한 검증용)
     * @param queueId        복사할 원본 큐 ID
     * @param targetSymbol   복사될 대상 코인 심볼
     * @param targetExchange 복사될 대상 거래소 (null 또는 공백이면 원본 큐의 거래소)
     * @param tradeMode      거래 모드 (MAIN/SIM)
     * @return 저장된 PatternQueue 엔티티
     * @throws CustomException 지원하지 않는 거래소(INVALID_EXCHANGE_TYPE), 대상 거래소에서 거래할 수 없는 종목(SYMBOL_NOT_TRADABLE)
     */
    @Transactional
    public PatternQueue copyQueue(Long userId, Long queueId, String targetSymbol, String targetExchange,
                                  TradeMode tradeMode) {
        // 원본 큐 조회
        PatternQueue origin = patternQueueRepository.findById(queueId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.QUEUE_NOT_FOUND));

        // 권한 검증
        if (!origin.getUserId().equals(userId)) {
            throw new CustomException(ExceptionMessage.QUEUE_UNAUTHORIZED);
        }

        // 대상 거래소 결정: 미지정이면 원본 거래소 유지, 지정 시 지원 여부 검증
        ExchangeType exchangeType = (targetExchange == null || targetExchange.isBlank())
                ? origin.getExchangeType()
                : ExchangeType.from(targetExchange);
        validateTradable(exchangeType, targetSymbol);

        // 대상 거래소/심볼 큐 1개 제한 검증
        long count = patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(
                userId, targetSymbol, tradeMode, exchangeType);
        if (count >= 1) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_QUEUES);
        }

        // 새 큐 생성 (심볼 변경, 비활성 상태)
        PatternQueue newQueue = new PatternQueue();
        newQueue.setUserId(userId);
        newQueue.setSymbol(targetSymbol);
        newQueue.setTriggerRate(origin.getTriggerRate());
        newQueue.setCycle(origin.isCycle()); // 사이클(단계 소진 후 재시작) 설정 유지
        newQueue.setVolumeStopEnabled(origin.isVolumeStopEnabled()); // 거래량 급변 중지 설정 유지 (중지 예약은 복사하지 않음)
        newQueue.setActive(false);
        newQueue.setTradeMode(tradeMode);
        newQueue.setExchangeType(exchangeType); // 대상 거래소 (미지정 시 원본 거래소)

        // 원본 패턴(단계 레벨, 블록 목록 포함) 딥 카피
        for (Pattern originPattern : origin.getPatterns()) {
            Pattern newPattern = new Pattern();
            newPattern.setQueue(newQueue);
            newPattern.setStepLevel(originPattern.getStepLevel());
            newPattern.setPatternOrder(originPattern.getPatternOrder());
            newPattern.setAmount(originPattern.getAmount());
            newPattern.setLeverage(originPattern.getLeverage());
            newPattern.setStopLossRate(originPattern.getStopLossRate());
            newPattern.setTakeProfitRate(originPattern.getTakeProfitRate());
            // 블록 목록은 새 리스트로 복사 (원본과 인스턴스 공유 방지)
            newPattern.setBlocks(new ArrayList<>(originPattern.getBlocks()));
            newQueue.getPatterns().add(newPattern);
        }

        // 가득 찬 상태는 원본 값 그대로 복사
        newQueue.setFull(origin.isFull());

        return saveNewQueue(newQueue);
    }

    /**
     * 기존 큐의 triggerRate와 단계/패턴/블록 구조를 교체한다.
     * symbol, tradeMode는 변경할 수 없다.
     *
     * <p>orphanRemoval = true가 설정되어 있으므로
     * patterns 컬렉션을 clear 후 재빌드하면
     * 기존 Pattern이 DB에서 자동 삭제된다.</p>
     *
     * <p>활성화된 큐는 수정할 수 없다. 먼저 비활성화 후 수정해야 한다.</p>
     *
     * @param userId  사용자 ID (권한 검증용)
     * @param queueId 수정할 큐 ID
     * @param request 수정 요청 (triggerRate, cycle, patterns)
     * @return 수정된 PatternQueue 엔티티
     */
    @Transactional
    public PatternQueue updateQueue(Long userId, Long queueId, UpdatePatternRequest request) {
        // 큐 존재 여부 검증
        PatternQueue queue = patternQueueRepository.findById(queueId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.QUEUE_NOT_FOUND));

        // 소유권 검증
        if (!queue.getUserId().equals(userId)) {
            throw new CustomException(ExceptionMessage.QUEUE_UNAUTHORIZED);
        }

        // 활성화된 큐는 수정 불가 (매매 중 구조 변경 방지)
        if (queue.isActive()) {
            throw new CustomException(ExceptionMessage.QUEUE_ACTIVE_CANNOT_UPDATE);
        }

        // 트리거 검증
        validateTrigger(request.getTriggerRate());
        // 패턴/블록 검증
        validatePatterns(request.getPatterns());

        // triggerRate, cycle, 거래량 급변 중지 설정 교체
        queue.setTriggerRate(request.getTriggerRate());
        queue.setCycle(request.isCycle());
        queue.setVolumeStopEnabled(request.isVolumeStopEnabled());

        // 기존 패턴 전체 삭제 (orphanRemoval이 DB 삭제 처리)
        queue.getPatterns().clear();
        // 삭제 DELETE가 새 패턴 INSERT보다 먼저 실행되도록 flush (UNIQUE(queue_id, step_level, pattern_order) 충돌 방지)
        patternQueueRepository.flush();

        // 새 패턴 빌드 후 큐에 추가
        buildPatterns(queue, request.getPatterns());

        // 검증 통과 = 모든 단계에 패턴이 정확히 2개 → 항상 가득 찬 상태
        queue.setFull(true);

        return patternQueueRepository.save(queue);
    }

    /**
     * 신규 큐를 저장하고 즉시 flush하여 유니크 제약 위반을 이 시점에 감지한다.
     * 개수 검증과 저장 사이에 같은 큐가 먼저 저장되면(중복 클릭 등 동시 요청)
     * DB 유니크 제약(user_id, exchange_type, symbol, trade_mode)이 막아주며, 이를 EXCEED_MAX_QUEUES로 변환한다.
     *
     * @param queue 저장할 신규 큐
     * @return 저장된 PatternQueue 엔티티
     * @throws CustomException 같은 거래소·심볼·거래 모드의 큐가 이미 저장된 경우(EXCEED_MAX_QUEUES)
     */
    private PatternQueue saveNewQueue(PatternQueue queue) {
        try {
            PatternQueue saved = patternQueueRepository.save(queue);
            // 커밋 시점이 아닌 여기서 INSERT를 실행해 제약 위반을 잡는다
            patternQueueRepository.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_QUEUES);
        }
    }

    // ─────────────────────────────────────────────
    // 검증 메서드
    // ─────────────────────────────────────────────

    /**
     * 거래소에서 거래할 수 있는 종목인지 검증한다.
     * [사용자 정책 2026-10-27] 대시보드 목록은 Bybit 기준이라 선택 거래소에 없는 종목이 들어올 수 있음 → 큐 생성 차단
     */
    private void validateTradable(ExchangeType exchangeType, String symbol) {
        if (!marketService.isTradable(exchangeType, symbol)) {
            throw new CustomException(ExceptionMessage.SYMBOL_NOT_TRADABLE);
        }
    }

    /**
     * 트리거 설정값 검증 (비율 > 0)
     */
    private void validateTrigger(BigDecimal triggerRate) {
        if (triggerRate == null || triggerRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ExceptionMessage.INVALID_TRIGGER_RATE);
        }
    }

    /**
     * 패턴 요청마다 패턴(블록 목록 포함)을 생성하여 큐에 추가한다.
     * addQueue와 updateQueue에서 공통으로 사용된다.
     *
     * @param queue    대상 큐
     * @param patterns 빌드할 패턴 요청 목록 (patternOrder는 같은 stepLevel 안에서 요청 순서대로 1부터 부여)
     */
    private void buildPatterns(PatternQueue queue, List<AddPatternRequest.PatternRequest> patterns) {
        // 단계별 다음 패턴 순번
        Map<Integer, Integer> nextOrderByStep = new HashMap<>();
        for (AddPatternRequest.PatternRequest patternReq : patterns) {
            int patternOrder = nextOrderByStep.merge(patternReq.getStepLevel(), 1, Integer::sum);

            Pattern pattern = new Pattern();
            pattern.setQueue(queue);
            pattern.setStepLevel(patternReq.getStepLevel());
            pattern.setPatternOrder(patternOrder);
            pattern.setAmount(patternReq.getAmount());
            pattern.setLeverage(patternReq.getLeverage());
            pattern.setStopLossRate(patternReq.getStopLossRate());
            pattern.setTakeProfitRate(patternReq.getTakeProfitRate());

            // 블록 목록 = 조건 블록(배열 순서 그대로, 없으면 생략) + 리프 블록(마지막)
            List<Side> blocks = new ArrayList<>();
            if (patternReq.getConditionBlocks() != null) {
                patternReq.getConditionBlocks().forEach(side -> blocks.add(Side.valueOf(side.toUpperCase())));
            }
            blocks.add(Side.valueOf(patternReq.getLeafBlock().toUpperCase()));
            pattern.setBlocks(blocks);

            queue.getPatterns().add(pattern);
        }
    }

    /**
     * 단계 번호별 패턴 개수를 센다.
     *
     * @param patterns 패턴 요청 목록
     * @return 단계 번호 → 패턴 개수
     */
    private Map<Integer, Long> countPatternsByStep(List<AddPatternRequest.PatternRequest> patterns) {
        return patterns.stream()
                .collect(Collectors.groupingBy(AddPatternRequest.PatternRequest::getStepLevel, Collectors.counting()));
    }

    /**
     * 패턴 목록 구조 검증 (단계 번호, 단계 수, 단계별 패턴 수, 개별 패턴)
     */
    private void validatePatterns(List<AddPatternRequest.PatternRequest> patterns) {
        // 패턴 1개 이상 검증
        if (patterns == null || patterns.isEmpty()) {
            throw new CustomException(ExceptionMessage.EMPTY_STEPS);
        }
        // 단계 번호 검증 (그룹핑 전에 null·0 이하 차단)
        for (AddPatternRequest.PatternRequest pattern : patterns) {
            if (pattern.getStepLevel() == null || pattern.getStepLevel() < 1) {
                throw new CustomException(ExceptionMessage.INVALID_STEP_LEVEL);
            }
        }

        // 단계 번호로 묶어 단계 수 1~20개 검증
        Map<Integer, Long> countByStep = countPatternsByStep(patterns);
        if (countByStep.size() > 20) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_STEPS);
        }

        // 단계별 패턴 정확히 2개 검증 (LONG 시작 1개 + SHORT 시작 1개)
        // 매칭 실패 시 같은 단계 내 반대 방향 패턴으로 전환할 수 있도록 양방향 보장 필요
        if (countByStep.values().stream().anyMatch(count -> count != 2)) {
            throw new CustomException(ExceptionMessage.INVALID_PATTERN_COUNT);
        }

        // 단계 번호 1..N 연속 검증 — 엔진은 "현재 단계 + 1"로 다음 단계를 찾으므로
        // 빈 번호가 있으면 이후 단계가 실행되지 않고, 1단계가 없으면 시작 자체가 불가
        for (int level = 1; level <= countByStep.size(); level++) {
            if (!countByStep.containsKey(level)) {
                throw new CustomException(ExceptionMessage.NON_CONSECUTIVE_STEP_LEVEL);
            }
        }

        for (AddPatternRequest.PatternRequest pattern : patterns) {
            validatePattern(pattern);
        }

        // 단계별 시작 블록(첫 조건 블록) 중복 검증 — 엔진은 신호 방향과 첫 조건 블록이 같은 패턴을 고르므로
        // 두 패턴이 같은 방향으로 시작하면 반대 방향 신호 때 선택할 패턴이 없음 (개별 검증 후라 조건 블록·side 유효)
        Map<Integer, Set<Side>> startSidesByStep = new HashMap<>();
        for (AddPatternRequest.PatternRequest pattern : patterns) {
            Side startSide = Side.valueOf(pattern.getConditionBlocks().get(0).toUpperCase());
            boolean added = startSidesByStep
                    .computeIfAbsent(pattern.getStepLevel(), level -> EnumSet.noneOf(Side.class))
                    .add(startSide);
            if (!added) {
                throw new CustomException(ExceptionMessage.DUPLICATE_START_BLOCK);
            }
        }
    }

    /**
     * 개별 패턴 검증 (금액, 레버리지, 손절률·익절률·강제청산 한도, 블록 구조, side)
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
        // 손절율 0% 초과 100% 미만 검증 — 투자금 기준이므로 100% 이상이면 전액 손실
        if (pattern.getStopLossRate() != null) {
            if (pattern.getStopLossRate().compareTo(BigDecimal.ZERO) <= 0
                    || pattern.getStopLossRate().compareTo(BigDecimal.valueOf(100)) >= 0) {
                throw new CustomException(ExceptionMessage.INVALID_STOP_LOSS_RATE);
            }
        }
        // 익절율 0% 초과 검증 — 0 이하면 익절 지정가가 진입가 반대편에 걸려 즉시 체결될 수 있음
        if (pattern.getTakeProfitRate() != null && pattern.getTakeProfitRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ExceptionMessage.INVALID_TAKE_PROFIT_RATE);
        }
        // 손절률 강제청산 한도 검증 — 거래소 안전망 손절(+0.5%p)이 강제청산가보다 안쪽이어야 함
        // 미입력이면 기본값(가격 8%)을 투자금 기준으로 환산해 검사
        BigDecimal resolvedStopLoss = TradeCalculator.resolveRoiRate(pattern.getStopLossRate(), pattern.getLeverage());
        if (!TradeCalculator.isStopLossWithinLimit(resolvedStopLoss, pattern.getLeverage())) {
            throw new CustomException(pattern.getStopLossRate() == null
                    ? ExceptionMessage.STOP_LOSS_REQUIRED_FOR_LEVERAGE
                    : ExceptionMessage.STOP_LOSS_EXCEEDS_LIQUIDATION);
        }
        // 리프 블록 필수
        if (pattern.getLeafBlock() == null) {
            throw new CustomException(ExceptionMessage.MISSING_LEAF_BLOCK);
        }
        // 조건 블록 1개 이상 — 엔진은 조건 블록 없는(리프만 있는) 패턴을 선택하지 못함
        List<String> conditionBlocks = pattern.getConditionBlocks();
        if (conditionBlocks == null || conditionBlocks.isEmpty()) {
            throw new CustomException(ExceptionMessage.EMPTY_CONDITION_BLOCKS);
        }
        // 조건 블록 최대 5개
        if (conditionBlocks.size() > 5) {
            throw new CustomException(ExceptionMessage.EXCEED_MAX_CONDITION_BLOCKS);
        }

        // side 검증 (조건 블록)
        for (String side : conditionBlocks) {
            validateSide(side);
        }
        // side 검증 (리프 블록)
        validateSide(pattern.getLeafBlock());
    }

    /**
     * side 값 검증 (LONG 또는 SHORT만 허용)
     */
    private void validateSide(String side) {
        // null이면 toUpperCase에서 NPE(500)가 나므로 먼저 차단
        if (side == null) {
            throw new CustomException(ExceptionMessage.INVALID_SIDE);
        }
        try {
            Side.valueOf(side.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ExceptionMessage.INVALID_SIDE);
        }
    }
}
