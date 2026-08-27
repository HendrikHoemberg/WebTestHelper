package dev.hendrikhoemberg.webtesthelper.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CredentialReference(String name, CredentialField field) {

    public static final Pattern PATTERN =
            Pattern.compile("\\{\\{cred\\.([a-z][a-z0-9_-]{0,31})\\.(username|password)\\}\\}");

    public static List<CredentialReference> findAll(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }
        Matcher matcher = PATTERN.matcher(template);
        List<CredentialReference> refs = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            CredentialField field = CredentialField.parse(matcher.group(2)).orElseThrow();
            refs.add(new CredentialReference(name, field));
        }
        return List.copyOf(refs);
    }

    public String token() {
        return "{{cred." + name + "." + field.token() + "}}";
    }
}
