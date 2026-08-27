package dev.hendrikhoemberg.webtesthelper.catalog;

public record ImapSettings(
        String host,
        int port,
        TlsMode tls,
        String username,
        String password,
        String folder,
        String verificationAddress
) {
    public boolean configured() {
        return host != null && !host.isBlank()
                && username != null && !username.isBlank()
                && verificationAddress != null && !verificationAddress.isBlank();
    }
}
