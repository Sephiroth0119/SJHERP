package com.sjherp.app.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class DemoCompanyPackTest {

    @Test
    void sunshine默认通润驱动且与qinghe经济口径一致() {
        assertThat(DemoCompanyPack.SUNSHINE.companyLabel()).isEqualTo("通润驱动");
        assertThat(DemoCompanyPack.SUNSHINE.warehouseName()).isEqualTo("通润驱动仓");
        assertThat(DemoCompanyPack.SUNSHINE.customerName()).isEqualTo("通润主机厂客户");
        assertThat(DemoCompanyPack.QINGHE.companyLabel()).isEqualTo("演示青禾食品");
        assertThat(DemoCompanyPack.SUNSHINE.purchaseQty())
                .isEqualByComparingTo(DemoCompanyPack.QINGHE.purchaseQty());
        assertThat(DemoCompanyPack.SUNSHINE.salesPrice())
                .isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(DemoCompanyPack.byId(null).id()).isEqualTo("sunshine");
        assertThat(DemoCompanyPack.byId("QINGHE").id()).isEqualTo("qinghe");
    }

    @Test
    void 空名称拒绝且通润驱动名称可通过() {
        assertThatThrownBy(() -> DemoCompanyPack.requireNonBlankName(" ", "仓库名称"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> DemoCompanyPack.requireNonBlankName(null, "仓库名称"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatCode(() -> DemoCompanyPack.requireNonBlankName("通润驱动仓", "仓库名称"))
                .doesNotThrowAnyException();
    }

    @Test
    void 未知包名拒绝() {
        assertThatThrownBy(() -> DemoCompanyPack.byId("acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知种子包");
    }
}
