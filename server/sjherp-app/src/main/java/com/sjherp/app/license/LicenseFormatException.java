package com.sjherp.app.license;

/**
 * license 文件字段不合法（缺签发对象、日期格式错误、版本不支持等）。
 */
public class LicenseFormatException extends RuntimeException {

    public LicenseFormatException(String message) {
        super(message);
    }

    public LicenseFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
