package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Optional;

@Getter
@Setter
@NoArgsConstructor
public class SettingsForm {

    private String host;
    private int port = 587;
    private TlsMode tls = TlsMode.STARTTLS;
    private String username;
    private String password = "";
    private String fromAddress;
    private String baseUrl;
    private String redirectAllMailTo;

    public static SettingsForm from(SmtpSettings smtp, String baseUrl, Optional<String> redirectAllMailTo) {
        SettingsForm form = new SettingsForm();
        if (smtp != null) {
            form.setHost(smtp.host());
            form.setPort(smtp.port() > 0 ? smtp.port() : 587);
            form.setTls(smtp.tls() != null ? smtp.tls() : TlsMode.STARTTLS);
            form.setUsername(smtp.username());
            // Password is intentionally left empty ("") on read/render
            form.setPassword("");
            form.setFromAddress(smtp.fromAddress());
        }
        form.setBaseUrl(baseUrl);
        form.setRedirectAllMailTo(redirectAllMailTo.orElse(""));
        return form;
    }
}
