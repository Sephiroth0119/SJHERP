package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordQuery;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gap.GapStatus;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 流程缺口仓储真实 MySQL 最小往返测试（X-2）：insert → findById → search 一条路径。
 */
class JdbcGapRecordRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcGapRecordRepository gapRecordRepository = new JdbcGapRecordRepository(jdbc);

    @Test
    void 缺口记录_保存后读回并可按状态与模块搜索() {
        String gapNo = "GAP-IT-" + uniqueSuffix();
        GapRecord record = new GapRecord(gapNo, null, "测试缺口",
                "用户想按批次号追溯出库记录", "系统支持按批次号查询出入库流水",
                "批次管理能力缺失", BusinessModule.INVENTORY, GapSeverity.MEDIUM,
                "tester", "tester");

        gapRecordRepository.save(record);

        assertThat(record.getId()).isNotNull();
        Optional<GapRecord> found = gapRecordRepository.findById(record.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getGapNo()).isEqualTo(gapNo);
        assertThat(found.get().getStatus()).isEqualTo(GapStatus.NEW);
        assertThat(found.get().getBusinessModule()).isEqualTo(BusinessModule.INVENTORY);
        assertThat(found.get().getSeverity()).isEqualTo(GapSeverity.MEDIUM);
        assertThat(found.get().getSessionId()).isNull();

        PageResult<GapRecord> page = gapRecordRepository.search(
                new GapRecordQuery(GapStatus.NEW, BusinessModule.INVENTORY, 1, 50));
        assertThat(page.items()).extracting(GapRecord::getGapNo).contains(gapNo);
    }
}
