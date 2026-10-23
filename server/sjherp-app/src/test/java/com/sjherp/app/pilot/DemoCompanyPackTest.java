package com.sjherp.app.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class DemoCompanyPackTest {

    @Test
    void sunshine与qinghe都是演示前缀且经济口径一致() {
        assertThat(DemoCompanyPack.SUNSHINE.companyLabel()).startsWith("演示");
        assertThat(DemoCompanyPack.QINGHE.companyLabel()).startsWith("演示");
        assertThat(DemoCompanyPack.SUNSHINE.purchaseQty())
                .isEqualByComparingTo(DemoCompanyPack.QINGHE.purchaseQty());
        assertThat(DemoCompanyPack.SUNSHINE.salesPrice())
                .isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(DemoCompanyPack.byId(null).id()).isEqualTo("sunshine");
        assertThat(DemoCompanyPack.byId("QINGHE").id()).isEqualTo("qinghe");
    }

    @Test
    void 空名称与无演示前缀均拒绝() {
        assertThatThrownBy(() -> DemoCompanyPack.requireDemoName(" ", "仓库名称"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> DemoCompanyPack.requireDemoName("阳光仓", "仓库名称"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("演示");
    }

    @Test
    void 未知包名拒绝() {
        assertThatThrownBy(() -> DemoCompanyPack.byId("acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知演示种子包");
    }
}
