package com.coin.webcointrader.common.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 시각을 직접 움직일 수 있는 테스트용 Clock (시세·거래 공통 추상 클래스 테스트에서 공용) */
public class MutableClock extends Clock {
    private long millis; // 현재 시각 (epoch ms)

    public MutableClock(long millis) { this.millis = millis; }

    public void advance(long ms) { millis += ms; }

    @Override public long millis() { return millis; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
