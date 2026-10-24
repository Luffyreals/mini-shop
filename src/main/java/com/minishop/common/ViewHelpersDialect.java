package com.minishop.common;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Set;

/**
 * Đưa các helper hiển thị vào template: {@code ${#vnd.format(p.price)}} và
 * {@code ${#vnDateTime.format(o.createdAt)}}.
 */
@Component
public class ViewHelpersDialect extends AbstractDialect implements IExpressionObjectDialect {

    private static final String VND = "vnd";
    private static final String VN_DATE_TIME = "vnDateTime";

    private final VndFormatter vndFormatter = new VndFormatter();
    private final VnDateTimeFormatter vnDateTimeFormatter = new VnDateTimeFormatter();

    public ViewHelpersDialect() {
        super("View helpers");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {
            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Set.of(VND, VN_DATE_TIME);
            }

            @Override
            public Object buildObject(IExpressionContext context, String expressionObjectName) {
                return switch (expressionObjectName) {
                    case VND -> vndFormatter;
                    case VN_DATE_TIME -> vnDateTimeFormatter;
                    default -> null;
                };
            }

            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }
}
