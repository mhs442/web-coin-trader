package com.coin.webcointrader.common.config;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 비동기 실행기 빈 설정.
 */
@Configuration
public class AsyncConfig {

    // 자동매매 청산 후처리 전용 단일 스레드 실행기
    private final ExecutorService closeRecordExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "close-record"));

    // 세션 레인 스레드 번호 (스레드 이름용)
    private final AtomicInteger laneThreadSeq = new AtomicInteger(1);

    // 자동매매 세션 레인 전용 고정 풀 (4개)
    private final ExecutorService sessionLaneExecutor = Executors.newFixedThreadPool(4,
            r -> new Thread(r, "session-lane-" + laneThreadSeq.getAndIncrement()));

    /**
     * 자동매매 청산 후처리(체결 조회 → 거래/투자 이력 저장) 전용 실행기.
     * WebSocket 스레드가 REST 조회 대기(최대 수 초)로 막히지 않도록 분리한다.
     * 단일 스레드라 제출 순서대로 저장된다.
     * 종료는 {@link #shutdownCloseRecordExecutor()}가 담당하므로 Spring 기본 destroy 추론은 끈다.
     */
    @Bean(destroyMethod = "")
    public ExecutorService closeRecordExecutor() {
        return closeRecordExecutor;
    }

    /**
     * 자동매매 세션 레인 실행기.
     * WebSocket 스레드는 세션별 최신 가격만 기록하고, 실제 처리(주문·체결 조회 포함)는 이 풀에서 수행한다.
     * 세션당 동시에 1개 작업만 실행되며(AutoTradeService.scheduleLane), 서로 다른 세션은 병렬 처리된다.
     * 종료는 {@link #shutdownCloseRecordExecutor()}가 담당하므로 Spring 기본 destroy 추론은 끈다.
     */
    @Bean(destroyMethod = "")
    public ExecutorService sessionLaneExecutor() {
        return sessionLaneExecutor;
    }

    /**
     * 애플리케이션 종료 시 진행 중인 세션 처리와 남은 이력 저장 작업이 끝나도록
     * 각각 최대 10초 기다린 뒤 강제 종료한다.
     * 레인을 먼저 닫아 새 청산 후처리 제출이 멈춘 뒤 이력 저장 실행기를 닫는다.
     */
    @PreDestroy
    public void shutdownCloseRecordExecutor() throws InterruptedException {
        sessionLaneExecutor.shutdown();
        if (!sessionLaneExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            sessionLaneExecutor.shutdownNow();
        }
        closeRecordExecutor.shutdown();
        if (!closeRecordExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            closeRecordExecutor.shutdownNow();
        }
    }
}
