package com.sjherp.app.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LicenseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void Spring选用带LicenseProperties的构造器() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LicenseProperties.class,
                    () -> new LicenseProperties(null, null, null, false, 14, 3600000));
            context.register(LicenseService.class);
            context.refresh();
            assertThat(context.getBean(LicenseService.class).current().status())
                    .isEqualTo(LicenseStatus.UNCONFIGURED);
        }
    }

    @Test
    void 路径未配置返回未配置() {
        LicenseService service = new LicenseService(
                new LicenseProperties(null, null, null, false, 14, 3600000),
                utc("2026-12-01"));
        LicenseEvaluation evaluation = service.current();
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.UNCONFIGURED);
        assertThat(evaluation.blocking()).isFalse();
    }

    @Test
    void 路径存在但文件缺失视为未配置() {
        Path missing = tempDir.resolve("absent.license.json");
        LicenseService service = new LicenseService(
                new LicenseProperties(missing.toString(), null, null, true, 14, 3600000),
                utc("2026-12-01"));
        assertThat(service.current().status()).isEqualTo(LicenseStatus.UNCONFIGURED);
        assertThat(service.current().blocking()).isFalse();
    }

    @Test
    void 读取有效文件并在status时重读() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path publicPem = tempDir.resolve("public.pem");
        Files.writeString(publicPem, toPublicPem(keys), StandardCharsets.UTF_8);
        LicenseDocument unsigned = LicenseDocument.unsigned(
                "试点客户", LocalDate.parse("2026-10-23"), LocalDate.parse("2027-01-01"),
                3, List.of("core"), 14, "dev-2026");
        LicenseDocument signed = unsigned.withSignature(LicenseCrypto.sign(unsigned, keys.getPrivate()));
        Path licenseFile = tempDir.resolve("sjherp.license.json");
        Files.writeString(licenseFile, signed.toJson(), StandardCharsets.UTF_8);

        LicenseService service = new LicenseService(
                new LicenseProperties(licenseFile.toString(), null, publicPem.toString(), false, 14, 3600000),
                utc("2026-12-01"));
        assertThat(service.current().status()).isEqualTo(LicenseStatus.VALID);
        assertThat(service.status().subject()).isEqualTo("试点客户");
        assertThat(service.status().seats()).isEqualTo(3);
    }

    @Test
    void 损坏JSON在强制模式下标记阻断() throws Exception {
        Path licenseFile = tempDir.resolve("bad.json");
        Files.writeString(licenseFile, "{", StandardCharsets.UTF_8);
        LicenseService service = new LicenseService(
                new LicenseProperties(licenseFile.toString(), null, null, true, 14, 3600000),
                utc("2026-12-01"));
        LicenseEvaluation evaluation = service.current();
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.MALFORMED);
        assertThat(evaluation.blocking()).isTrue();
    }

    private static Clock utc(String date) {
        return Clock.fixed(Instant.parse(date + "T00:00:00Z"), ZoneOffset.UTC);
    }

    private static String toPublicPem(KeyPair keys) {
        String body = java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keys.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }
}
