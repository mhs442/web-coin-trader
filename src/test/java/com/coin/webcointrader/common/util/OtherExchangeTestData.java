package com.coin.webcointrader.common.util;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 두 번째 거래소 데이터를 흉내 내는 테스트 헬퍼. (거래소 조건 조회·삭제 검증용)
 *
 * <p>ExchangeType 에는 아직 BYBIT 하나뿐이라, 행의 exchange_type 을 enum 에 없는 'OTHER' 로 바꿔 다른 거래소 행으로 쓴다.
 * 테스트 H2 스키마는 Hibernate 가 exchange_type 을 enum('BYBIT') 타입 + CHECK 제약으로 만들므로
 * 해당 테이블의 제약을 먼저 풀어야 한다. (H2 전용 SQL, 운영 DB 와 무관)</p>
 *
 * <p>'OTHER' 행은 엔티티로 매핑할 수 없으므로 건수로만 확인한다.</p>
 */
public final class OtherExchangeTestData {

    public static final String OTHER = "OTHER"; // enum 에 없는 다른 거래소 값

    private OtherExchangeTestData() {}

    /**
     * 테이블의 exchange_type 컬럼이 'OTHER' 를 받도록 CHECK 제약을 지우고 타입을 VARCHAR(20)로 바꾼다. (반복 호출해도 무방)
     * H2 의 DDL 은 진행 중인 트랜잭션을 커밋하므로, 테스트 데이터를 저장하기 전에 호출해야 롤백 대상에서 빠지지 않는다.
     *
     * @param jdbcTemplate JdbcTemplate
     * @param table        테이블명 (예: investment_history)
     */
    public static void allowAnyExchangeType(JdbcTemplate jdbcTemplate, String table) {
        // exchange_type 을 검사하는 CHECK 제약만 골라 삭제 (제약 이름은 H2 가 자동 생성)
        jdbcTemplate.queryForList("""
                SELECT tc.CONSTRAINT_NAME
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                WHERE tc.TABLE_NAME = UPPER(?) AND UPPER(cc.CHECK_CLAUSE) LIKE '%EXCHANGE_TYPE%'
                """, String.class, table)
                .forEach(name -> jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name + "\""));
        jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN exchange_type SET DATA TYPE VARCHAR(20)");
    }

    /**
     * 행의 거래소를 다른 거래소('OTHER')로 바꾼다. 먼저 {@link #allowAnyExchangeType} 으로 제약을 풀어 둬야 한다.
     * JPA 로 저장한 행이면 호출 전에 flush 해야 한다.
     *
     * @param jdbcTemplate JdbcTemplate (테스트 트랜잭션과 같은 커넥션 사용)
     * @param table        테이블명 (예: investment_history)
     * @param id           대상 행 ID
     */
    public static void setOtherExchange(JdbcTemplate jdbcTemplate, String table, Long id) {
        jdbcTemplate.update("UPDATE " + table + " SET exchange_type = ? WHERE id = ?", OTHER, id);
    }

    /**
     * 다른 거래소('OTHER') 행을 SQL 로 지운다.
     * 엔티티로 불러오는 정리 코드(repository.deleteAll 등)는 'OTHER' 를 enum 으로 바꾸지 못해 실패하므로 그 전에 호출한다.
     *
     * @param jdbcTemplate JdbcTemplate
     * @param table        테이블명
     */
    public static void deleteOtherExchangeRows(JdbcTemplate jdbcTemplate, String table) {
        jdbcTemplate.update("DELETE FROM " + table + " WHERE exchange_type = ?", OTHER);
    }
}
