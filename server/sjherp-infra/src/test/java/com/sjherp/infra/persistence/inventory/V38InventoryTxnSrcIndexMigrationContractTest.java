package com.sjherp.infra.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class V38InventoryTxnSrcIndexMigrationContractTest {

    @Test
    void addsOnlySrcLookupIndex() throws Exception {
        String sql = new String(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V38__inventory_txn_src_index.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("idx_inventory_txn_src");
        assertThat(sql).contains("src_doc_no");
        assertThat(sql).contains("src_line_no");
        assertThat(sql).containsIgnoringCase("ALTER TABLE inventory_transaction");
        assertThat(sql).doesNotContainIgnoringCase("DROP ");
        assertThat(sql).doesNotContainIgnoringCase("DELETE ");
        assertThat(sql).doesNotContain("FLOAT");
        assertThat(sql).doesNotContain("DOUBLE");
    }
}
