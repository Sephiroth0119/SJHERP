package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.sjherp.app.consistency.ConsistencyCheckDao.ProductionInventoryGlRow;

class ConsistencyCheckDaoTest {

    @Test
    void productionInventoryGlMatches_使用凭证表真实来源类型列名() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of()).when(jdbc).query(anyString(),
                ArgumentMatchers.<RowMapper<ProductionInventoryGlRow>>any());

        new ConsistencyCheckDao(jdbc).productionInventoryGlMatches();

        @SuppressWarnings("unchecked")
        var invocation = (org.mockito.invocation.Invocation) org.mockito.Mockito
                .mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query"))
                .findFirst()
                .orElseThrow();
        String sql = invocation.getArgument(0, String.class);

        assertThat(sql)
                .contains("origin_v.source_doc_type = 'PRODUCTION_COST_SETTLEMENT'")
                .doesNotContain("origin_v.source_type");
    }
}
