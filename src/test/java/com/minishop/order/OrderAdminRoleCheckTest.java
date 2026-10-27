package com.minishop.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.catalog.ProductRepository;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.AdminAccess;
import com.minishop.support.LogCapture;
import com.minishop.user.Role;
import com.minishop.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-009-9, BR-009-3 (ADR-0014): every public method of {@link OrderAdminService} calls
 * {@link AdminAccess#requireAdmin()} first. The methods are found by reflection, so a method added later is checked
 * without anyone remembering to add a test. A refused call gets the {@link AccessDeniedException} of the session
 * (not a parse or lookup error, even with arguments that are invalid on purpose) and touches no repository: no lock,
 * no read, no write.
 */
@ExtendWith(MockitoExtension.class)
class OrderAdminRoleCheckTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    AdminAccess adminAccess;

    /** Every public method declared by the service. */
    static Stream<Method> publicMethods() {
        return Arrays.stream(OrderAdminService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge())
                .sorted(Comparator.comparing(Method::getName));
    }

    /** Arguments that are invalid on purpose: a refused call must not even look at them. */
    private static Object[] invalidArguments(Method method) {
        return Arrays.stream(method.getParameterTypes()).map(type -> {
            if (type == long.class) {
                return (Object) (-1L);
            } else if (type == int.class) {
                return -1;
            } else if (type == boolean.class) {
                return false;
            } else if (type == String.class) {
                return "not a status or a page";
            }
            return null;
        }).toArray();
    }

    private static void invoke(Object service, Method method) throws Throwable {
        try {
            method.invoke(service, invalidArguments(method));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void reflection_findsThePublicApi() {
        assertThat(publicMethods().map(Method::getName))
                .contains("listOrders", "getOrder", "dashboard", "changeStatus");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicMethods")
    void everyPublicMethod_whenRoleCheckRefuses_throwsItAndTouchesNothing(Method method) {
        AccessDeniedException denied = new AccessDeniedException();
        when(adminAccess.requireAdmin()).thenThrow(denied);
        OrderAdminService service = new OrderAdminService(orderRepository, productRepository, adminAccess);

        assertThatThrownBy(() -> invoke(service, method)).isSameAs(denied);

        verify(adminAccess, times(1)).requireAdmin();
        verifyNoInteractions(orderRepository, productRepository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicMethods")
    void everyPublicMethod_whenAdmin_asksTheRoleCheckExactlyOnce(Method method) {
        when(adminAccess.requireAdmin()).thenReturn(77L);
        OrderAdminService service = new OrderAdminService(orderRepository, productRepository, adminAccess);

        try {
            invoke(service, method);
        } catch (Throwable other) {
            // the invalid arguments may make the real work fail; only "not refused" and "asked once" matter here
            assertThat(other).isNotInstanceOf(AccessDeniedException.class);
        }

        verify(adminAccess, times(1)).requireAdmin();
    }

    @Test
    void changeStatus_logsTheAdminIdOfTheSession() {
        when(adminAccess.requireAdmin()).thenReturn(42L);
        User customer = new User("buyer@order.test", "hash", "Nguyễn Văn An", Role.USER);
        ReflectionTestUtils.setField(customer, "id", 2L);
        Order order = new Order(customer, "Trần Thị Bình", "0912345678", "12 Lê Lợi, Quận 1", new BigDecimal("450000"));
        ReflectionTestUtils.setField(order, "id", 5L);
        when(orderRepository.lockById(5L)).thenReturn(Optional.of(order));
        OrderAdminService service = new OrderAdminService(orderRepository, productRepository, adminAccess);

        try (LogCapture logs = LogCapture.of(LoggerFactory.getLogger(OrderAdminService.class))) {
            service.changeStatus(5, "CONFIRMED");

            assertThat(logs.events(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement().asString().startsWith("Admin order status: admin=42, order=5");
        }
    }
}
