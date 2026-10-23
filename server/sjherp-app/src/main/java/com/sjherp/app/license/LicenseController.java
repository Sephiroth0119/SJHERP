package com.sjherp.app.license;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 离线许可只读状态（M8-T08）。ADMIN/BOSS 查看；无下单/支付入口。
 */
@RestController
@RequestMapping("/api/license")
@PreAuthorize("@perm.has('license:view')")
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping("/status")
    public LicenseDtos.StatusResponse status() {
        return licenseService.status();
    }
}
