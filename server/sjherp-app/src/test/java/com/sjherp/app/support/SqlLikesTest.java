package com.sjherp.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlLikesTest {

    @Test
    void 百分号与下划线被转义() {
        assertThat(SqlLikes.contains("100%_off")).isEqualTo("%100\\%\\_off%");
        assertThat(SqlLikes.escape("a\\b")).isEqualTo("a\\\\b");
    }

    @Test
    void 普通关键字只做包含包裹() {
        assertThat(SqlLikes.contains("  钢板  ")).isEqualTo("%钢板%");
    }
}
