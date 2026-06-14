package com.sjherp.domain.production;

/**
 * BOM 不存在异常（M5-T01，映射为 404）。
 */
public class BillOfMaterialsNotFoundException extends RuntimeException {

    private BillOfMaterialsNotFoundException(String message) {
        super(message);
    }

    public static BillOfMaterialsNotFoundException byId(long id) {
        return new BillOfMaterialsNotFoundException("BOM 不存在: id=" + id);
    }
}
