package com.sjherp.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * M8-T05：热路径索引必须写在 Flyway 脚本里（不依赖 Docker）。
 * 与 {@code deploy/perf/required-indexes.txt} / {@code ./deploy/perf.sh --check} 同一份清单。
 */
class RequiredLedgerIndexCatalogTest {

    private static final List<String> REQUIRED_INDEXES = List.of(
            "uk_inventory_balance_dim",
            "uk_inventory_txn_idempotency",
            "idx_inventory_txn_dim",
            "idx_inventory_txn_src",
            "uk_voucher_doc_no",
            "uk_voucher_source",
            "idx_voucher_period",
            "idx_voucher_status",
            "uk_voucher_line",
            "idx_voucher_line_head",
            "idx_voucher_line_account",
            "uk_purchase_receipt_doc_no",
            "idx_purchase_receipt_po",
            "uk_sales_delivery_doc_no",
            "idx_sales_delivery_order");

    @Test
    void flywaySqlDeclaresHotPathIndexes() throws IOException {
        String sql = readMigrations(
                "/db/migration/V10__inventory.sql",
                "/db/migration/V14__purchase_receipt.sql",
                "/db/migration/V17__sales_delivery.sql",
                "/db/migration/V19__general_ledger.sql",
                "/db/migration/V20__auto_voucher_accounts.sql",
                "/db/migration/V38__inventory_txn_src_index.sql");
        for (String index : REQUIRED_INDEXES) {
            assertThat(sql)
                    .as("Flyway 必须声明热路径索引 %s（M8-T05）", index)
                    .contains(index);
        }
        Path catalog = Path.of("").toAbsolutePath().getParent().getParent()
                .resolve("deploy/perf/required-indexes.txt");
        assertThat(catalog).as("deploy/perf/required-indexes.txt").exists();
        String listed = Files.readString(catalog);
        for (String index : REQUIRED_INDEXES) {
            assertThat(listed)
                    .as("required-indexes.txt 必须列出 %s", index)
                    .contains(index);
        }
    }

    private static String readMigrations(String... classpathFiles) {
        List<String> chunks = new ArrayList<>();
        for (String path : classpathFiles) {
            try (InputStream in = RequiredLedgerIndexCatalogTest.class.getResourceAsStream(path)) {
                assertThat(in).as("找不到迁移脚本 %s", path).isNotNull();
                chunks.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(path, e);
            }
        }
        return String.join("\n", chunks);
    }
}
