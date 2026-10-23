package com.sjherp.app.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线签发/校验 CLI。测试密钥仅用于 CI 与文档样例，生产私钥不要放进仓库。
 *
 * <pre>
 *   java ... LicenseCli issue --subject 某某五金 --expires 2027-10-23 \
 *     --key deploy/license/dev-ed25519-private.pem --out /tmp/sjherp.license.json
 *   java ... LicenseCli verify --file /tmp/sjherp.license.json \
 *     --public deploy/license/dev-ed25519-public.pem
 * </pre>
 */
public final class LicenseCli {

    private LicenseCli() {
    }

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) throws Exception {
        if (args == null || args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return args == null || args.length == 0 ? 1 : 0;
        }
        String command = args[0].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "issue" -> issue(args);
            case "verify" -> verify(args);
            default -> {
                System.err.println("未知命令：" + command);
                printUsage();
                yield 1;
            }
        };
    }

    private static int issue(String[] args) throws Exception {
        String subject = null;
        String issued = LocalDate.now(Clock.systemUTC()).toString();
        String expires = null;
        String seats = null;
        String modules = null;
        String grace = "14";
        String kid = "dev-2026";
        String key = "deploy/license/dev-ed25519-private.pem";
        String out = null;
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                System.err.println("参数缺少值：" + flag);
                return 1;
            }
            String value = args[++i];
            switch (flag) {
                case "--subject" -> subject = value;
                case "--issued" -> issued = value;
                case "--expires" -> expires = value;
                case "--seats" -> seats = value;
                case "--modules" -> modules = value;
                case "--grace" -> grace = value;
                case "--kid" -> kid = value;
                case "--key" -> key = value;
                case "--out" -> out = value;
                default -> {
                    System.err.println("未知参数：" + flag);
                    return 1;
                }
            }
        }
        if (subject == null || subject.isBlank() || out == null || out.isBlank()) {
            System.err.println("issue 需要 --subject 与 --out");
            return 1;
        }
        PrivateKey privateKey = LicenseCrypto.privateKeyFromPem(
                Files.readString(Path.of(key), StandardCharsets.UTF_8));
        List<String> moduleList = new ArrayList<>();
        if (modules != null && !modules.isBlank()) {
            for (String item : modules.split(",")) {
                if (!item.isBlank()) {
                    moduleList.add(item.strip());
                }
            }
        }
        LicenseDocument unsigned = LicenseDocument.unsigned(
                subject,
                LocalDate.parse(issued),
                blankDate(expires),
                blankInt(seats),
                moduleList,
                blankInt(grace),
                kid);
        String signature = LicenseCrypto.sign(unsigned, privateKey);
        LicenseDocument signed = unsigned.withSignature(signature);
        Path output = Path.of(out);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, signed.toJson(), StandardCharsets.UTF_8);
        System.out.println("已签发 " + output.toAbsolutePath());
        return 0;
    }

    private static int verify(String[] args) throws Exception {
        String file = null;
        String publicPemPath = "deploy/license/dev-ed25519-public.pem";
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                System.err.println("参数缺少值：" + flag);
                return 1;
            }
            String value = args[++i];
            switch (flag) {
                case "--file" -> file = value;
                case "--public" -> publicPemPath = value;
                default -> {
                    System.err.println("未知参数：" + flag);
                    return 1;
                }
            }
        }
        if (file == null) {
            System.err.println("verify 需要 --file");
            return 1;
        }
        PublicKey publicKey = LicenseCrypto.publicKeyFromPem(
                Files.readString(Path.of(publicPemPath), StandardCharsets.UTF_8));
        String json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        LicenseEvaluation evaluation = LicenseEvaluator.evaluate(
                json, publicKey, Clock.systemUTC(), 14, false);
        System.out.println(evaluation.status().name() + " " + evaluation.message());
        return evaluation.status() == LicenseStatus.VALID || evaluation.status() == LicenseStatus.GRACE
                ? 0 : 1;
    }

    private static LocalDate blankDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static Integer blankInt(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }

    private static void printUsage() {
        System.out.println("""
                SJHERP 离线 license CLI（测试密钥仅用于样例）
                用法：
                  issue --subject 名称 [--issued YYYY-MM-DD] [--expires YYYY-MM-DD]
                        [--seats N] [--modules a,b] [--grace 14] [--kid dev-2026]
                        --key private.pem --out file.json
                  verify --file file.json --public public.pem
                """);
    }
}
