package dev.hendrikhoemberg.webtesthelper.reporting;

import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;

import java.util.Map;

/**
 * Extension of {@link StandardLinkBuilder} that allows context-relative links (e.g. {@code @{/css/app.css}})
 * to be processed in non-web contexts (such as PDF generation or email rendering).
 * In a web context, it delegates to {@link StandardLinkBuilder#computeContextPath};
 * in non-web contexts, it returns an empty context path rather than throwing an exception.
 */
public class LenientStandardLinkBuilder extends StandardLinkBuilder {

    @Override
    protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
        if (context instanceof IWebContext) {
            return super.computeContextPath(context, base, parameters);
        }
        return "";
    }
}
