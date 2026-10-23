package com.minishop.common;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Set;

/** Đưa các helper hiển thị vào template: {@code ${#vnd.format(p.price)}}. */
@Component
public class ViewHelpersDialect extends AbstractDialect implements IExpressionObjectDialect {

    private static final String VND = "vnd";

    private final VndFormatter vndFormatter = new VndFormatter();

    public ViewHelpersDialect() {
        super("View helpers");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {
            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Set.of(VND);
            }

            @Override
            public Object buildObject(IExpressionContext context, String expressionObjectName) {
                return VND.equals(expressionObjectName) ? vndFormatter : null;
            }

            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }
}
