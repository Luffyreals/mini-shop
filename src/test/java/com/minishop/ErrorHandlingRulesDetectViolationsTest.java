package com.minishop;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the three error-handling rules of {@link ArchitectureTest} (spec 007 section 8) really go red. A
 * rule that matches nothing stays green forever, so each rule is run against small classes that break it (and
 * against ones that do not). The classes are nested in this test class, which Spring Boot's test filter keeps out
 * of component scanning, so they never become beans of another test's context.
 */
class ErrorHandlingRulesDetectViolationsTest {

    @ControllerAdvice
    static class RogueAdvice {
    }

    @RestControllerAdvice
    static class RogueRestAdvice {
    }

    @Controller
    static class ControllerWithExceptionHandler {

        @ExceptionHandler(IllegalStateException.class)
        String handle() {
            return "x";
        }
    }

    @Controller
    static class ControllerCatchingExceptions {

        String catchesException() {
            try {
                return work();
            } catch (Exception e) {
                return "x";
            }
        }

        String catchesRuntimeException() {
            try {
                return work();
            } catch (RuntimeException e) {
                return "x";
            }
        }

        String catchesThrowable() {
            try {
                return work();
            } catch (Throwable e) {
                return "x";
            }
        }

        String work() {
            return "ok";
        }
    }

    @Controller
    static class ControllerCatchingSpecificExceptionsOnly {

        String catchesSpecific() {
            try {
                return work();
            } catch (IllegalStateException | IllegalArgumentException e) {
                return "x";
            }
        }

        String work() {
            return "ok";
        }
    }

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    void onlyCommonDeclaresControllerAdvice_flagsAdviceOutsideCommon() {
        assertThatThrownBy(() -> ArchitectureTest.onlyCommonDeclaresControllerAdvice.check(classes(RogueAdvice.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("RogueAdvice");
        assertThatThrownBy(() -> ArchitectureTest.onlyCommonDeclaresControllerAdvice
                .check(classes(RogueRestAdvice.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("RogueRestAdvice");
    }

    @Test
    void exceptionHandlersOnlyInCommon_flagsAnExceptionHandlerInAController() {
        assertThatThrownBy(() -> ArchitectureTest.exceptionHandlersOnlyInCommon
                .check(classes(ControllerWithExceptionHandler.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("handle");
    }

    @Test
    void controllers_doNotCatchGenericExceptions_flagsEachGenericCatch() {
        assertThatThrownBy(() -> ArchitectureTest.controllers_doNotCatchGenericExceptions
                .check(classes(ControllerCatchingExceptions.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("catchesException")
                .hasMessageContaining("catchesRuntimeException")
                .hasMessageContaining("catchesThrowable");
    }

    @Test
    void controllers_doNotCatchGenericExceptions_acceptsSpecificCatches() {
        assertThatCode(() -> ArchitectureTest.controllers_doNotCatchGenericExceptions
                .check(classes(ControllerCatchingSpecificExceptionsOnly.class))).doesNotThrowAnyException();
    }
}
