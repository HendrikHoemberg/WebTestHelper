package dev.hendrikhoemberg.webtesthelper.catalog;

public record SmtpSettings(
        String host,
        int port,
        TlsMode tls,
        String username,
        String password,
        String fromAddress
) {
    public boolean configured() {
        return host != null && !host.isBlank() && fromAddress != null && !fromAddress.isBlank();
    }
}
