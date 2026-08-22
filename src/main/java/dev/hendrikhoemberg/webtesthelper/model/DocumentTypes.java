package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Locale;
import java.util.Set;

public final class DocumentTypes {

    private static final Set<String> DOCUMENT_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "csv");

    private DocumentTypes() {
    }

    public static boolean isDocument(NormalizedUrl url) {
        String extension = extensionOf(url.path());
        return extension != null && DOCUMENT_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static boolean isPdf(NormalizedUrl url) {
        String extension = extensionOf(url.path());
        return extension != null && "pdf".equals(extension.toLowerCase(Locale.ROOT));
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        return path.substring(dot + 1);
    }
}
