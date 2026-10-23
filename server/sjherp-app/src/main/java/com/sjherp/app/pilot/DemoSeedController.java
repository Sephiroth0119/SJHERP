package com.sjherp.app.pilot;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.pilot.DemoSeedDtos.PackInfo;
import com.sjherp.app.pilot.DemoSeedDtos.SeedRequest;
import com.sjherp.app.pilot.DemoSeedDtos.SeedResponse;
import com.sjherp.app.security.CurrentUser;

/**
 * 演示种子试点入口（M8-T07）：ADMIN/BOSS 显式装载虚构企业数据。
 *
 * <p>不提供关账；关账仍走 {@code /api/gl/periods/{period}/close-precheck} 与 close。
 */
@RestController
@RequestMapping("/api/pilot/demo-seed")
@PreAuthorize("@perm.has('pilot:seed')")
public class DemoSeedController {

    private final DemoSeedService demoSeedService;

    public DemoSeedController(DemoSeedService demoSeedService) {
        this.demoSeedService = demoSeedService;
    }

    @GetMapping("/packs")
    public List<PackInfo> packs() {
        return demoSeedService.listPacks();
    }

    @PostMapping
    public SeedResponse seed(@RequestBody(required = false) SeedRequest request) {
        String pack = request == null ? null : request.pack();
        return SeedResponse.from(demoSeedService.seed(pack, CurrentUser.operator()));
    }
}
