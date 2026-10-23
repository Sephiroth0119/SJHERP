package com.sjherp.app.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseCliTest {

    @TempDir
    Path tempDir;

    @Test
    void 签发后可用同一公钥校验() throws Exception {
        Path key = tempDir.resolve("private.pem");
        Path pub = tempDir.resolve("public.pem");
        Files.write(key, resource("license/dev-ed25519-private.pem"));
        Files.write(pub, resource("license/dev-ed25519-public.pem"));
        Path out = tempDir.resolve("issued.json");
        int issued = LicenseCli.run(new String[] {
                "issue",
                "--subject", "CLI试点客户",
                "--issued", "2026-10-23",
                "--expires", "2027-10-23",
                "--seats", "8",
                "--modules", "core,finance",
                "--grace", "14",
                "--kid", "dev-2026",
                "--key", key.toString(),
                "--out", out.toString()
        });
        assertThat(issued).isZero();
        LicenseDocument document = LicenseDocument.parse(Files.readString(out, StandardCharsets.UTF_8));
        assertThat(document.subject()).isEqualTo("CLI试点客户");
        assertThat(document.seats()).isEqualTo(8);
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                document.toJson(),
                LicenseCrypto.publicKeyFromPem(Files.readString(pub, StandardCharsets.UTF_8)),
                Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC),
                14,
                false);
        assertThat(evaluation.status()).isEqualTo(LicenseStatus.VALID);
    }

    private static byte[] resource(String name) throws Exception {
        try (var stream = LicenseCliTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }
}
