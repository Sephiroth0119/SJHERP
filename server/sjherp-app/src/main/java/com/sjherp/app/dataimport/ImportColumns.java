package com.sjherp.app.dataimport;

import java.util.Set;

/**
 * 导入列名常量（包私有，M2-T09）：{@link ExcelWorkbookReader} 与 {@link ExcelTemplateWriter}
 * 均从此处引用列名，保证两处口径一致，避免列名漂移导致「模板与解析不匹配」的隐患。
 */
final class ImportColumns {

    private ImportColumns() {
    }

    // ---- 商品档案 ----
    static final String PRODUCT_CODE = "商品编码";
    static final String PRODUCT_NAME = "商品名称";
    static final String PRODUCT_UNIT = "基本单位";
    static final String PRODUCT_SPEC = "规格型号";
    static final String PRODUCT_BARCODE = "条码";
    static final String PRODUCT_REMARK = "备注";

    static final String[] PRODUCT_HEADERS = {
            PRODUCT_CODE, PRODUCT_NAME, PRODUCT_UNIT, PRODUCT_SPEC, PRODUCT_BARCODE, PRODUCT_REMARK
    };
    static final Set<String> PRODUCT_REQUIRED = Set.of(PRODUCT_NAME, PRODUCT_UNIT);

    // ---- 客户档案 ----
    static final String CUSTOMER_CODE = "客户编码";
    static final String CUSTOMER_NAME = "客户名称";
    static final String CUSTOMER_SETTLEMENT = "结算方式";
    static final String CUSTOMER_CONTACT_PERSON = "联系人";
    static final String CUSTOMER_CONTACT_PHONE = "联系电话";
    static final String CUSTOMER_ADDRESS = "地址";
    static final String CUSTOMER_TAX_NO = "税号";
    static final String CUSTOMER_CREDIT_LIMIT = "信用额度";

    static final String[] CUSTOMER_HEADERS = {
            CUSTOMER_CODE, CUSTOMER_NAME, CUSTOMER_SETTLEMENT,
            CUSTOMER_CONTACT_PERSON, CUSTOMER_CONTACT_PHONE,
            CUSTOMER_ADDRESS, CUSTOMER_TAX_NO, CUSTOMER_CREDIT_LIMIT
    };
    static final Set<String> CUSTOMER_REQUIRED = Set.of(CUSTOMER_NAME, CUSTOMER_SETTLEMENT);

    // ---- 供应商档案 ----
    static final String SUPPLIER_CODE = "供应商编码";
    static final String SUPPLIER_NAME = "供应商名称";
    static final String SUPPLIER_SETTLEMENT = "结算方式";
    static final String SUPPLIER_CONTACT_PERSON = "联系人";
    static final String SUPPLIER_CONTACT_PHONE = "联系电话";
    static final String SUPPLIER_ADDRESS = "地址";
    static final String SUPPLIER_TAX_NO = "税号";

    static final String[] SUPPLIER_HEADERS = {
            SUPPLIER_CODE, SUPPLIER_NAME, SUPPLIER_SETTLEMENT,
            SUPPLIER_CONTACT_PERSON, SUPPLIER_CONTACT_PHONE,
            SUPPLIER_ADDRESS, SUPPLIER_TAX_NO
    };
    static final Set<String> SUPPLIER_REQUIRED = Set.of(SUPPLIER_NAME, SUPPLIER_SETTLEMENT);

    // ---- 期初库存 ----
    static final String OPENING_WAREHOUSE = "仓库";
    static final String OPENING_PRODUCT = "商品";
    static final String OPENING_QUANTITY = "期初数量";
    static final String OPENING_UNIT_COST = "期初单价";

    static final String[] OPENING_STOCK_HEADERS = {
            OPENING_WAREHOUSE, OPENING_PRODUCT, OPENING_QUANTITY, OPENING_UNIT_COST
    };
    static final Set<String> OPENING_STOCK_REQUIRED = Set.of(
            OPENING_WAREHOUSE, OPENING_PRODUCT, OPENING_QUANTITY, OPENING_UNIT_COST
    );
}
