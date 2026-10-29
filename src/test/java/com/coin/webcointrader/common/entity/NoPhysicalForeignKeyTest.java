package com.coin.webcointrader.common.entity;

import com.coin.webcointrader.common.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 물리 외래키 금지 정책 검증.
 * JPA 연관관계(Pattern.queue, SimWallet.user)는 유지하되, Hibernate가 스키마를 만들 때 FK 제약을 생성하지 않아야 한다.
 */
@DataJpaTest
@Import(JpaConfig.class)
class NoPhysicalForeignKeyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("엔티티로 생성한 스키마에 FOREIGN KEY 제약이 하나도 없다")
    void schemaHasNoForeignKey() {
        // when: 테스트 DB(H2, create-drop)에 생성된 FK 제약 조회
        List<String> foreignKeys = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME || '.' || CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_TYPE = 'FOREIGN KEY'",
                String.class);

        // then
        assertThat(foreignKeys).isEmpty();
    }
}
