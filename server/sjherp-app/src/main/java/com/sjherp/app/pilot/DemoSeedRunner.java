package com.sjherp.app.pilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 可选启动装载（M8-T07）。仅 {@code sjherp.demo-seed.enabled=true} 时运行，默认关闭。
 */
@Component
@Order
@ConditionalOnProperty(prefix = "sjherp.demo-seed", name = "enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final String OPERATOR = "demo-seed";

    private final DemoSeedService demoSeedService;
    private final DemoSeedProperties properties;

    public DemoSeedRunner(DemoSeedService demoSeedService, DemoSeedProperties properties) {
        this.demoSeedService = demoSeedService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        DemoSeedResult result = demoSeedService.seed(properties.pack(), OPERATOR);
        log.info("演示种子装载完成 pack={} alreadyLoaded={} period={} closeable={} po={}",
                result.packId(), result.alreadyLoaded(), result.period(), result.periodCloseable(),
                result.purchaseOrderNo());
    }
}
