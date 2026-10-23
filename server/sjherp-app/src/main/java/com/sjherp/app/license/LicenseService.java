package com.sjherp.app.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 启动加载 + 定时/按请求轻量重读 license 文件。缺文件=未配置，不挡业务。
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    static final String DEFAULT_PUBLIC_KEY_RESOURCE = "/license/dev-ed25519-public.pem";

    private final LicenseProperties properties;
    private final Clock clock;
    private final AtomicReference<LicenseEvaluation> snapshot = new AtomicReference<>();

    public LicenseService(LicenseProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LicenseService(LicenseProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.snapshot.set(refresh());
        logStatus(this.snapshot.get(), true);
    }

    public LicenseEvaluation current() {
        return snapshot.get();
    }

    public LicenseDtos.StatusResponse status() {
        return refresh().toResponse();
    }

    @Scheduled(fixedDelayString = "${sjherp.license.refresh-ms:3600000}")
    public void scheduledRefresh() {
        logStatus(refresh(), false);
    }

    public LicenseEvaluation refresh() {
        LicenseEvaluation evaluated = evaluateNow();
        snapshot.set(evaluated);
        return evaluated;
    }

    private LicenseEvaluation evaluateNow() {
        PublicKey publicKey;
        try {
            publicKey = loadPublicKey();
        } catch (RuntimeException ex) {
            return LicenseEvaluator.failed(
                    LicenseStatus.MALFORMED,
                    properties.enforce(),
                    "未加载验签公钥：" + ex.getMessage());
        }
        String json;
        try {
            json = readLicenseJson();
        } catch (RuntimeException ex) {
            return LicenseEvaluator.failed(
                    LicenseStatus.MALFORMED,
                    properties.enforce(),
                    "无法读取 license 文件：" + ex.getMessage());
        }
        return LicenseEvaluator.evaluate(
                json,
                publicKey,
                clock,
                properties.defaultGraceDays(),
                properties.enforce());
    }

    private String readLicenseJson() {
        String configured = properties.path();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Path.of(configured);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new LicenseFormatException("无法读取 license 文件：" + path, ex);
        }
    }

    PublicKey loadPublicKey() {
        String pem = properties.publicKey();
        if (pem != null && pem.contains("BEGIN PUBLIC KEY")) {
            return LicenseCrypto.publicKeyFromPem(pem);
        }
        String keyPath = properties.publicKeyPath();
        if (keyPath != null && !keyPath.isBlank()) {
            Path path = Path.of(keyPath);
            if (!Files.exists(path)) {
                throw new LicenseFormatException("验签公钥文件不存在：" + path);
            }
            try {
                return LicenseCrypto.publicKeyFromPem(Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new LicenseFormatException("无法读取验签公钥：" + path, ex);
            }
        }
        try (InputStream stream = LicenseService.class.getResourceAsStream(DEFAULT_PUBLIC_KEY_RESOURCE)) {
            if (stream == null) {
                throw new LicenseFormatException("缺少内置测试验签公钥");
            }
            return LicenseCrypto.publicKeyFromPem(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new LicenseFormatException("无法读取内置测试验签公钥", ex);
        }
    }

    private void logStatus(LicenseEvaluation evaluation, boolean startup) {
        String prefix = startup ? "启动许可校验" : "定期许可校验";
        if (evaluation.status() == LicenseStatus.VALID || evaluation.status() == LicenseStatus.UNCONFIGURED) {
            log.info("{}：{} — {}", prefix, evaluation.status().label(), evaluation.message());
            return;
        }
        if (evaluation.blocking()) {
            log.error("{}：{} — {}（硬阻断已开启）", prefix, evaluation.status().label(), evaluation.message());
            return;
        }
        log.warn("{}：{} — {}", prefix, evaluation.status().label(), evaluation.message());
    }
}
