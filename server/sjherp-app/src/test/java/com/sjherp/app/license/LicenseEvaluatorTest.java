package com.sjherp.app.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LicenseEvaluatorTest {

    private static KeyPair keys;

    @BeforeAll
    static void generateKeys() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void 未配置不挡业务_即使开启硬阻断() {
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(null, keys.getPublic(), utc("2026-12-01"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.UNCONFIGURED);
        assertThat(evaluation.configured()).isFalse();
        assertThat(evaluation.blocking()).isFalse();
        assertThat(evaluation.message()).contains("不挡业务");
    }

    @Test
    void 空白文件视为未配置() {
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate("  \n", keys.getPublic(), utc("2026-12-01"), 14, false);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.UNCONFIGURED);
        assertThat(evaluation.blocking()).isFalse();
    }

    @Test
    void 有效签名未到期为VALID() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2027-10-23"), 14));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2026-12-01"), 14, false);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(evaluation.blocking()).isFalse();
        assertThat(evaluation.subject()).isEqualTo("演示阳光文具");
        assertThat(evaluation.seats()).isEqualTo(5);
        assertThat(evaluation.modules()).containsExactly(
                "core", "finance", "inventory", "production", "purchase", "sales");
        assertThat(evaluation.graceUntil()).isEqualTo(LocalDate.parse("2027-11-06"));
    }

    @Test
    void 无到期日视为长期有效() {
        LicenseDocument signed = sign(LicenseDocument.unsigned(
                "试点客户", LocalDate.parse("2026-10-23"), null, null, List.of(), null, "dev-2026"));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2029-01-01"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(evaluation.blocking()).isFalse();
        assertThat(evaluation.expiresAt()).isNull();
    }

    @Test
    void 到期日后宽限期内为GRACE且不阻断() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2026-10-23"), 14));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2026-10-30"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.GRACE);
        assertThat(evaluation.blocking()).isFalse();
        assertThat(evaluation.graceUntil()).isEqualTo(LocalDate.parse("2026-11-06"));
    }

    @Test
    void 过期超过宽限_未强制时不阻断() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2026-01-01"), 7));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2026-02-01"), 14, false);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(evaluation.blocking()).isFalse();
    }

    @Test
    void 过期超过宽限_强制时阻断() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2026-01-01"), 7));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2026-02-01"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(evaluation.blocking()).isTrue();
    }

    @Test
    void 坏签名_强制时阻断() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2027-10-23"), 14));
        String tampered = signed.toJson().replace("演示阳光文具", "被篡改客户");
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                tampered, keys.getPublic(), utc("2026-12-01"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.INVALID_SIGNATURE);
        assertThat(evaluation.blocking()).isTrue();
    }

    @Test
    void 坏签名_未强制时仅提示() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2027-10-23"), 14));
        String tampered = signed.toJson().replace("演示阳光文具", "被篡改客户");
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                tampered, keys.getPublic(), utc("2026-12-01"), 14, false);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.INVALID_SIGNATURE);
        assertThat(evaluation.blocking()).isFalse();
    }

    @Test
    void 无法解析为MALFORMED() {
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                "{not-json", keys.getPublic(), utc("2026-12-01"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.MALFORMED);
        assertThat(evaluation.blocking()).isTrue();
        assertThat(evaluation.configured()).isTrue();
    }

    @Test
    void 到期日当天仍有效() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2026-10-23"), 14));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2026-10-23"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(evaluation.blocking()).isFalse();
    }

    @Test
    void 宽限末日仍为GRACE() {
        LicenseDocument signed = sign(sample(LocalDate.parse("2026-10-23"), 14));
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                signed.toJson(), keys.getPublic(), utc("2026-11-06"), 14, true);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.GRACE);
        assertThat(evaluation.blocking()).isFalse();
    }

    private static LicenseDocument sample(LocalDate expiresAt, int graceDays) {
        return LicenseDocument.unsigned(
                "演示阳光文具",
                LocalDate.parse("2026-10-23"),
                expiresAt,
                5,
                List.of("sales", "purchase", "core", "finance", "inventory", "production"),
                graceDays,
                "dev-2026");
    }

    private static LicenseDocument sign(LicenseDocument unsigned) {
        return unsigned.withSignature(LicenseCrypto.sign(unsigned, keys.getPrivate()));
    }

    private static Clock utc(String date) {
        return Clock.fixed(Instant.parse(date + "T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void 内置样例与openssl签名互认() throws Exception {
        PublicKey publicKey = LicenseCrypto.publicKeyFromPem(resource("license/dev-ed25519-public.pem"));
        String json = resource("license/sample.license.json");
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(json, publicKey, utc("2026-12-01"), 14, false);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(evaluation.subject()).isEqualTo("演示阳光文具");
        assertThat(evaluation.kid()).isEqualTo("dev-2026");
    }

    private static String resource(String name) throws Exception {
        try (var stream = LicenseEvaluatorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
