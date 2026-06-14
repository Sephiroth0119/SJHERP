package com.sjherp.domain.production;

import java.util.List;

/**
 * 创建/更新 BOM 的命令对象（不可变，从 app 层传入领域服务）。
 *
 * @param productId 父件商品 id
 * @param version   版本号（正整数）
 * @param remark    备注（可空）
 * @param lines     BOM 行命令列表（不可为空）
 */
public record BillOfMaterialsCommand(
        long productId,
        int version,
        String remark,
        List<BomLineCommand> lines) {
}
