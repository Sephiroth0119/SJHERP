package com.sjherp.domain.production;

/**
 * 工艺路线不存在异常（M5-T01，映射为 404）。
 */
public class RoutingNotFoundException extends RuntimeException {

    private RoutingNotFoundException(String message) {
        super(message);
    }

    public static RoutingNotFoundException byId(long id) {
        return new RoutingNotFoundException("工艺路线不存在: id=" + id);
    }
}
