package com.sjherp.app.license;

import java.util.List;

public final class LicenseDtos {

    private LicenseDtos() {
    }

    public record StatusResponse(
            String status,
            String statusLabel,
            boolean configured,
            boolean enforce,
            boolean blocking,
            String subject,
            String issuedAt,
            String expiresAt,
            String graceUntil,
            Integer seats,
            List<String> modules,
            String kid,
            String message) {
    }
}
