package com.sjherp.domain.production;

import java.util.List;

/**
 * 创建/更新工艺路线的命令对象。
 *
 * @param productId  产品 id
 * @param version    版本号（正整数）
 * @param remark     备注（可空）
 * @param operations 工序命令列表（不可为空）
 */
public record RoutingCommand(
        long productId,
        int version,
        String remark,
        List<RoutingOperationCommand> operations) {
}
