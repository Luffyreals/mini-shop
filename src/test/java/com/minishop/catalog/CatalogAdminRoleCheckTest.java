package com.minishop.catalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.AdminAccess;
import com.minishop.storage.StorageService;
import com.minishop.support.LogCapture;
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
 * AC-009-9, BR-009-3 (ADR-0014): every public method of the two catalog admin services calls
 * {@link AdminAccess#requireAdmin()} first. The methods are found by reflection, so a method added later is checked
 * without anyone remembering to add a test. A refused call gets the {@link AccessDeniedException} of the session
 * (not any other error, even with arguments that are invalid on purpose) and touches no repository and no storage:
 * nothing is locked, read or written.
 */
@ExtendWith(MockitoExtension.class)
class CatalogAdminRoleCheckTest {

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    StorageService storage;

    @Mock
    AdminAccess adminAccess;

    private Object serviceOf(Class<?> type) {
        if (type == CategoryAdminService.class) {
            return new CategoryAdminService(categoryRepository, productRepository, adminAccess);
        }
        return new ProductAdminService(productRepository, categoryRepository, storage, new ProductImages(storage),
                adminAccess);
    }

    /** Every public method declared by the services: {@code (service class, method)}. */
    static Stream<Object[]> publicMethods() {
        return Stream.of(CategoryAdminService.class, ProductAdminService.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge())
                        .sorted(Comparator.comparing(Method::getName))
                        .map(m -> new Object[]{type, m}));
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
                return "not valid";
            }
            return null;
        }).toArray();
    }

    @Test
    void reflection_findsThePublicApiOfBothServices() {
        assertThat(publicMethods().map(args -> ((Class<?>) args[0]).getSimpleName() + "." + ((Method) args[1]).getName()))
                .contains("CategoryAdminService.list", "CategoryAdminService.create", "CategoryAdminService.delete",
                        "ProductAdminService.list", "ProductAdminService.create", "ProductAdminService.adjustStock",
                        "ProductAdminService.lowStock");
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("publicMethods")
    void everyPublicMethod_whenRoleCheckRefuses_throwsItAndTouchesNothing(Class<?> type, Method method) {
        AccessDeniedException denied = new AccessDeniedException();
        when(adminAccess.requireAdmin()).thenThrow(denied);
        Object service = serviceOf(type);

        assertThatThrownBy(() -> invoke(service, method)).isSameAs(denied);

        verify(adminAccess, times(1)).requireAdmin();
        verifyNoInteractions(categoryRepository, productRepository, storage);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("publicMethods")
    void everyPublicMethod_whenAdmin_asksTheRoleCheckExactlyOnce(Class<?> type, Method method) {
        when(adminAccess.requireAdmin()).thenReturn(77L);
        Object service = serviceOf(type);

        try {
            invoke(service, method);
        } catch (Throwable other) {
            // the invalid arguments may make the real work fail; only "not refused" and "asked once" matter here
            assertThat(other).isNotInstanceOf(AccessDeniedException.class);
        }

        verify(adminAccess, times(1)).requireAdmin();
    }

    private static void invoke(Object service, Method method) throws Throwable {
        try {
            method.invoke(service, invalidArguments(method));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ---------- the id comes from the session ----------

    @Test
    void categoryDelete_logsTheAdminIdOfTheSession() {
        when(adminAccess.requireAdmin()).thenReturn(42L);
        Category existing = new Category("Đồ chơi", null);
        ReflectionTestUtils.setField(existing, "id", 5L);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(productRepository.countByCategoryId(5L)).thenReturn(0L);
        CategoryAdminService service = new CategoryAdminService(categoryRepository, productRepository, adminAccess);

        try (LogCapture logs = LogCapture.of(LoggerFactory.getLogger(CategoryAdminService.class))) {
            service.delete(5);

            assertThat(logs.events(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Admin action: admin=42, category=5, action=delete");
        }
    }

    @Test
    void productSetActive_logsTheAdminIdOfTheSession() {
        when(adminAccess.requireAdmin()).thenReturn(42L);
        Product product = new Product(new Category("Áo", null), "Áo thun", "Mô tả", new BigDecimal("100000"), 3);
        ReflectionTestUtils.setField(product, "id", 7L);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product));
        ProductAdminService service = (ProductAdminService) serviceOf(ProductAdminService.class);

        try (LogCapture logs = LogCapture.of(LoggerFactory.getLogger(ProductAdminService.class))) {
            service.setActive(7, false);

            assertThat(logs.events(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Admin action: admin=42, product=7, action=hide");
        }
    }
}
