package com.coin.webcointrader.common.autotrade.util;

import java.util.Arrays;

/**
 * 심볼 하나의 최근 60초 체결량 집계 창.
 * 1초 버킷 60개짜리 원형 배열로, 체결 1건 반영은 O(1)이다.
 * 체결 수신 스레드(add)와 세션 레인(sum·isFull)이 동시에 접근하므로 메서드 단위로 동기화한다.
 */
public class VolumeWindow {

    // [사용자 정책 2026-10-29] 거래량 측정 구간 60초
    public static final int WINDOW_SECONDS = 60;

    private final long startedAtMs;                                // 집계 시작 시각 (구독 시작, epoch ms)
    private final long[] bucketSeconds = new long[WINDOW_SECONDS]; // 슬롯별로 담고 있는 epoch 초 (-1: 비어 있음)
    private final double[] bucketVolumes = new double[WINDOW_SECONDS]; // 슬롯별 체결량 합 (코인 수량)
    private long latestSecond = Long.MIN_VALUE;                    // 지금까지 받은 체결 중 가장 늦은 epoch 초

    /**
     * @param startedAtMs 집계 시작 시각 (epoch ms)
     */
    public VolumeWindow(long startedAtMs) {
        this.startedAtMs = startedAtMs;
        Arrays.fill(bucketSeconds, -1L);
    }

    /**
     * 체결 1건을 반영한다.
     *
     * @param tradeTimeMs 체결 시각 (epoch ms)
     * @param qty         체결 수량 (코인 기준)
     */
    public synchronized void add(long tradeTimeMs, double qty) {
        long second = tradeTimeMs / 1000;
        // 창보다 오래된 지연 체결은 최신 슬롯을 덮어쓰지 않도록 버린다
        if (latestSecond != Long.MIN_VALUE && second <= latestSecond - WINDOW_SECONDS) {
            return;
        }
        latestSecond = Math.max(latestSecond, second);
        int slot = (int) Math.floorMod(second, (long) WINDOW_SECONDS);
        // 슬롯이 다른 초(60초 이전)를 담고 있으면 비우고 새로 집계
        if (bucketSeconds[slot] != second) {
            bucketSeconds[slot] = second;
            bucketVolumes[slot] = 0;
        }
        bucketVolumes[slot] += qty;
    }

    /**
     * 기준 시각까지 최근 60초(현재 초 포함)의 체결량 합을 반환한다.
     *
     * @param nowMs 기준 시각 (epoch ms)
     * @return 체결량 합 (코인 기준)
     */
    public synchronized double sum(long nowMs) {
        long nowSecond = nowMs / 1000;
        double total = 0;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            // 창(nowSecond-59 ~ nowSecond) 안의 초를 담은 슬롯만 합산
            long second = bucketSeconds[i];
            if (second > nowSecond - WINDOW_SECONDS && second <= nowSecond) {
                total += bucketVolumes[i];
            }
        }
        return total;
    }

    /**
     * 집계 시작 후 60초가 지나 창이 다 찼는지 반환한다. (워밍업 완료 여부)
     *
     * @param nowMs 기준 시각 (epoch ms)
     * @return 60초 이상 집계했으면 true
     */
    public boolean isFull(long nowMs) {
        return nowMs - startedAtMs >= WINDOW_SECONDS * 1000L;
    }
}
