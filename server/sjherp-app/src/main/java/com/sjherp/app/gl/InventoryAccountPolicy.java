package com.sjherp.app.gl;

import java.util.Objects;

import com.sjherp.domain.catalog.InventoryCategory;

/**
 * 存货分类到总账科目的固定会计政策。
 *
 * <p>原材料归集到 1403，其他可销售或可交付存货归集到 1405。
 * 此处刻意不接受任意科目编码，避免商品档案绕过会计科目表和既定核算口径。
 */
public final class InventoryAccountPolicy {

    public static final String RAW_MATERIAL_ACCOUNT = "1403";
    public static final String GOODS_ACCOUNT = "1405";

    public String accountFor(InventoryCategory category) {
        Objects.requireNonNull(category, "存货分类不能为空");
        return category == InventoryCategory.RAW_MATERIAL ? RAW_MATERIAL_ACCOUNT : GOODS_ACCOUNT;
    }
}
