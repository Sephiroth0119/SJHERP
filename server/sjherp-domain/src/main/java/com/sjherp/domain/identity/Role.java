package com.sjherp.domain.identity;

/**
 * 系统角色（M2-T05）。
 *
 * <p>小企业从简：角色为固定枚举，不做自定义角色。功能权限点与角色的
 * 映射（M2-T06 权限模型）后续在此基础上扩展；当前仅 ADMIN 有特殊语义
 * （用户管理 API 限定 ADMIN）。
 */
public enum Role {

    /** 管理员：用户管理、系统配置 */
    ADMIN("管理员"),

    /** 老板：全业务可见（数据范围控制留 M2-T06） */
    BOSS("老板"),

    /** 会计：财务核心操作 */
    ACCOUNTANT("会计"),

    /** 仓管：库存收发与盘点 */
    WAREHOUSE("仓管"),

    /** 采购：采购订单与入库 */
    PURCHASER("采购"),

    /** 销售：销售订单与出库 */
    SALES("销售");

    /** 用户可见的中文名称 */
    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
