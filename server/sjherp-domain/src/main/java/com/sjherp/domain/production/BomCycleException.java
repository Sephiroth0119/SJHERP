package com.sjherp.domain.production;

/**
 * BOM 环形依赖异常（M5-T01）。
 *
 * <p>触发场景：
 * <ul>
 *   <li>保存时（create/update）：子件的 active BOM 树中含当前父件，形成间接环；</li>
 *   <li>展开时（explode）：访问路径集合中发现重复节点，防御历史脏数据/跨版本成环。</li>
 * </ul>
 */
public class BomCycleException extends RuntimeException {

    public BomCycleException(String message) {
        super(message);
    }
}
