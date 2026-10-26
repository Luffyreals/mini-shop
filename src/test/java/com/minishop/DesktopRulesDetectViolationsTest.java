package com.minishop;

import com.minishop.user.CurrentUser;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.Annotation;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that each desktop rule of {@link ArchitectureTest} (ADR-0014, plan D1 T4a) really goes red. A rule that
 * matches nothing stays green forever, so every rule is run against a small class that breaks it, and against a
 * class that follows it.
 *
 * <p>The classes under test are written as class files by the JDK {@code java.lang.classfile} API into a temporary
 * directory and imported from there. Real source fixtures would not do: a fixture that depends on
 * {@code org.springframework.web} cannot compile (the web stack is gone, ADR-0014); a fixture in
 * {@code com.minishop.admin} cannot be reached from {@code desktop} (package-private); and a fixture with
 * {@code @Service} or {@code @Entity} in {@code com.minishop..} would be picked up by component or entity scanning
 * of other tests. A class file that only names a type needs that type neither on the classpath nor accessible.
 */
class DesktopRulesDetectViolationsTest {

    private static final String LABEL = "javafx.scene.control.Label";
    private static final String TASK = "javafx.concurrent.Task";
    private static final String STRING_PROPERTY = "javafx.beans.property.SimpleStringProperty";

    @TempDir
    Path classesDirectory;

    // ------------------------------------------------------------------ rule 1

    @Test
    void javafxOnlyInDesktopAndUiClasses_flagsAFeatureClassThatImportsJavaFx() throws IOException {
        JavaClasses classes = classes(type("com.minishop.catalog.CatalogHelper").field(LABEL));

        assertRed(ArchitectureTest.javafx_onlyInDesktopAndUiClasses, classes, "CatalogHelper");
    }

    @Test
    void javafxOnlyInDesktopAndUiClasses_flagsAClassWhoseNameOnlyContainsAUiWord() throws IOException {
        JavaClasses classes = classes(type("com.minishop.cart.ScreenFactory").field(LABEL));

        assertRed(ArchitectureTest.javafx_onlyInDesktopAndUiClasses, classes, "ScreenFactory");
    }

    @Test
    void javafxOnlyInDesktopAndUiClasses_acceptsUiClassesAndTheirNestedClassesAndDesktop() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.catalog.ProductScreen").field(LABEL),
                type("com.minishop.catalog.ProductScreen$1").field(LABEL),
                type("com.minishop.order.OrderDrawer").field(LABEL),
                type("com.minishop.user.LoginDialog").field(LABEL),
                type("com.minishop.cart.CartPresenter").field(STRING_PROPERTY),
                type("com.minishop.desktop.AnythingInDesktop").field(LABEL),
                type("com.minishop.catalog.PlainClass").field("java.lang.String"));

        assertGreen(ArchitectureTest.javafx_onlyInDesktopAndUiClasses, classes);
    }

    // ------------------------------------------------------------------ rule 2

    @Test
    void presenters_flagsEveryJavaFxPackageExceptBeansAndCollections() throws IOException {
        for (String forbidden : List.of(LABEL, TASK, "javafx.stage.Stage", "javafx.fxml.FXMLLoader",
                "javafx.application.Platform", "javafx.animation.Timeline", "javafx.geometry.Insets",
                "javafx.util.Duration", "javafx.event.ActionEvent")) {
            JavaClasses classes = classes(type("com.minishop.catalog.ProductPresenter").field(forbidden));

            assertRed(ArchitectureTest.presenters_useOnlyJavaFxBeansAndCollections, classes,
                    "ProductPresenter");
        }
    }

    @Test
    void presenters_flagsANestedClassOfAPresenter() throws IOException {
        JavaClasses classes = classes(type("com.minishop.desktop.ShellPresenter$Row").field(LABEL));

        assertRed(ArchitectureTest.presenters_useOnlyJavaFxBeansAndCollections, classes, "ShellPresenter$Row");
    }

    @Test
    void presenters_acceptsBeansPropertiesAndOtherClasses() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.catalog.ProductPresenter").field(STRING_PROPERTY)
                        .field("javafx.beans.binding.Bindings").field("javafx.collections.FXCollections"),
                type("com.minishop.catalog.ProductScreen").field(LABEL));

        assertGreen(ArchitectureTest.presenters_useOnlyJavaFxBeansAndCollections, classes);
    }

    @Test
    void presenters_flagsPlatformAlone_andNamesOnlyThePresenter() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.catalog.ProductPresenter").field("javafx.application.Platform"),
                type("com.minishop.catalog.OtherPresenter").field(STRING_PROPERTY));

        assertRed(ArchitectureTest.presenters_useOnlyJavaFxBeansAndCollections, classes, "ProductPresenter");
        assertThatThrownBy(() -> ArchitectureTest.presenters_useOnlyJavaFxBeansAndCollections.check(classes))
                .hasMessageNotContaining("OtherPresenter");
    }

    // ------------------------------------------------------------------ rule 2b (AC-009-11)

    @Test
    void uiNodes_flagAScreenDrawerOrDialogThatDependsOnAService() throws IOException {
        for (String node : List.of("ProductScreen", "OrderDrawer", "LoginDialog", "ProductScreen$1")) {
            JavaClasses classes = classes(
                    type("com.minishop.catalog." + node).field(LABEL).field("com.minishop.catalog.FakeService"),
                    type("com.minishop.catalog.FakeService").annotation("org.springframework.stereotype.Service"));

            assertRed(ArchitectureTest.uiNodes_doNotDependOnServices, classes, node);
        }
    }

    @Test
    void uiNodes_acceptAPresenterThatDependsOnAServiceAndNodesThatDoNot() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.catalog.ProductPresenter").field("com.minishop.catalog.FakeService"),
                type("com.minishop.catalog.ProductScreen").field(LABEL).field("com.minishop.catalog.ProductPresenter")
                        .field("com.minishop.catalog.CategoryOption"),
                type("com.minishop.cart.ScreenFactory").field("com.minishop.cart.FakeService"),
                type("com.minishop.catalog.FakeService").annotation("org.springframework.stereotype.Service"));

        assertGreen(ArchitectureTest.uiNodes_doNotDependOnServices, classes);
    }

    @Test
    void uiNodes_acceptADesktopDialogThatDependsOnAService() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.desktop.ErrorDialog").field("com.minishop.desktop.FakeService"),
                type("com.minishop.desktop.FakeService").annotation("org.springframework.stereotype.Service"));

        assertGreen(ArchitectureTest.uiNodes_doNotDependOnServices, classes);
    }

    // ------------------------------------------------------------------ rule 3

    @Test
    void services_flagsAServiceThatImportsJavaFx() throws IOException {
        JavaClasses classes = classes(type("com.minishop.catalog.FakeService")
                .annotation("org.springframework.stereotype.Service").field(LABEL));

        assertRed(ArchitectureTest.services_doNotDependOnJavaFx, classes, "FakeService");
    }

    @Test
    void services_acceptsAServiceWithoutJavaFx() throws IOException {
        JavaClasses classes = classes(type("com.minishop.catalog.FakeService")
                .annotation("org.springframework.stereotype.Service").field("java.lang.String"));

        assertGreen(ArchitectureTest.services_doNotDependOnJavaFx, classes);
    }

    @Test
    void entities_flagsAnEntityThatImportsJavaFx() throws IOException {
        JavaClasses classes = classes(type("com.minishop.catalog.FakeEntity")
                .annotation("jakarta.persistence.Entity").field("javafx.beans.property.StringProperty"));

        assertRed(ArchitectureTest.entities_doNotDependOnJavaFx, classes, "FakeEntity");
    }

    @Test
    void repositories_flagsARepositoryThatImportsJavaFx() throws IOException {
        JavaClasses classes = classes(type("com.minishop.catalog.FakeRepository").asInterface()
                .implementing("org.springframework.data.repository.Repository").field(LABEL));

        assertRed(ArchitectureTest.repositories_doNotDependOnJavaFx, classes, "FakeRepository");
    }

    @Test
    void commonConfigAndStorage_flagEachPackageThatImportsJavaFx() throws IOException {
        for (String owner : List.of("common", "config", "storage")) {
            JavaClasses classes = classes(type("com.minishop." + owner + ".Helper").field(LABEL));

            assertRed(ArchitectureTest.commonConfigAndStorage_doNotDependOnJavaFx, classes, "Helper");
        }
    }

    @Test
    void commonConfigAndStorage_acceptPlainJava() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.common.Helper").field("java.time.Clock"),
                type("com.minishop.storage.Helper").field("java.nio.file.Path"));

        assertGreen(ArchitectureTest.commonConfigAndStorage_doNotDependOnJavaFx, classes);
    }

    @Test
    void common_flagsSpringWeb() throws IOException {
        JavaClasses classes = classes(type("com.minishop.common.WebHelper")
                .field("org.springframework.web.servlet.ModelAndView"));

        assertRed(ArchitectureTest.common_doesNotDependOnSpringWeb, classes, "WebHelper");
    }

    @Test
    void common_acceptsSpringBeansAndCrypto() throws IOException {
        JavaClasses classes = classes(type("com.minishop.common.Helper")
                .field("org.springframework.stereotype.Component")
                .field("org.springframework.security.crypto.password.PasswordEncoder"));

        assertGreen(ArchitectureTest.common_doesNotDependOnSpringWeb, classes);
    }

    // ------------------------------------------------------------------ rules 4 and 5

    @Test
    void desktop_flagsEachFeatureItMustNotKnow() throws IOException {
        List<String> forbidden = List.of(
                "com.minishop.catalog.Product", "com.minishop.cart.CartCheckout",
                "com.minishop.order.OrderAdminService", "com.minishop.user.CurrentUser",
                "com.minishop.admin.AdminInput", "com.minishop.storage.StorageService",
                "com.minishop.config.SeedProperties");
        for (String type : forbidden) {
            JavaClasses classes = classes(type("com.minishop.desktop.Shell").field(type));

            assertRed(ArchitectureTest.desktop_dependsOnNoFeature, classes, "Shell");
        }
    }

    @Test
    void desktop_acceptsCommonAndItself() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.desktop.Shell").field("com.minishop.common.SafeExceptionLog")
                        .field("com.minishop.desktop.Navigator").field("com.minishop.MiniShopApplication"));

        assertGreen(ArchitectureTest.desktop_dependsOnNoFeature, classes);
    }

    @Test
    void commonAndConfig_flagADependencyOnDesktop() throws IOException {
        for (String owner : List.of("common", "config")) {
            JavaClasses classes = classes(type("com.minishop." + owner + ".Helper")
                    .field("com.minishop.desktop.Toasts"));

            assertRed(ArchitectureTest.commonAndConfig_doNotDependOnDesktop, classes, "Helper");
        }
    }

    @Test
    void commonAndConfig_acceptTheirOwnPackages() throws IOException {
        JavaClasses classes = classes(type("com.minishop.config.Helper").field("com.minishop.common.FormText"));

        assertGreen(ArchitectureTest.commonAndConfig_doNotDependOnDesktop, classes);
    }

    // ------------------------------------------------------------------ rule 6

    @Test
    void uiClasses_flagARepositoryDependency() throws IOException {
        JavaClasses classes = classes(type("com.minishop.catalog.ProductScreen")
                .field("com.minishop.catalog.ProductRepository"));

        assertRed(ArchitectureTest.uiClasses_doNotUseRepositoriesOrEntities, classes, "ProductScreen");
    }

    @Test
    void uiClasses_flagAnEntityDependency() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.order.OrderDrawer").field("com.minishop.catalog.Product"),
                type("com.minishop.user.LoginDialog").field("com.minishop.user.User"),
                type("com.minishop.cart.CartPresenter").field("com.minishop.catalog.Category"));

        assertRed(ArchitectureTest.uiClasses_doNotUseRepositoriesOrEntities, classes, "OrderDrawer");
        assertRed(ArchitectureTest.uiClasses_doNotUseRepositoriesOrEntities, classes, "LoginDialog");
        assertRed(ArchitectureTest.uiClasses_doNotUseRepositoriesOrEntities, classes, "CartPresenter");
    }

    @Test
    void uiClasses_acceptServicesAndRecords() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.catalog.ProductScreen").field("com.minishop.catalog.CategoryAdminService")
                        .field("com.minishop.catalog.CategoryOption"),
                type("com.minishop.catalog.ProductService").field("com.minishop.catalog.ProductRepository"));

        assertGreen(ArchitectureTest.uiClasses_doNotUseRepositoriesOrEntities, classes);
    }

    // ------------------------------------------------------------------ rule 7

    @Test
    void javafxConcurrent_flagsAnyClassOutsideDesktop() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.order.CheckoutScreen").field(TASK),
                type("com.minishop.catalog.ProductPresenter").field("javafx.concurrent.Service"));

        assertRed(ArchitectureTest.javafxConcurrent_onlyInDesktop, classes, "CheckoutScreen");
        assertRed(ArchitectureTest.javafxConcurrent_onlyInDesktop, classes, "ProductPresenter");
    }

    @Test
    void javafxConcurrent_acceptsDesktop() throws IOException {
        JavaClasses classes = classes(
                type("com.minishop.desktop.FxBackgroundTasks").field(TASK),
                type("com.minishop.catalog.PlainClass").field("java.lang.String"));

        assertGreen(ArchitectureTest.javafxConcurrent_onlyInDesktop, classes);
    }

    // ------------------------------------------------------------------ CurrentUser

    @Test
    void currentUser_flagsAClassThatIsNotARecord() throws IOException {
        JavaClasses classes = classes(type("com.minishop.user.CurrentUser").field("java.lang.String"));

        assertRed(ArchitectureTest.currentUser_isARecord, classes, "CurrentUser");
    }

    @Test
    void currentUser_acceptsARecordAndTheRealClass() throws IOException {
        JavaClasses synthetic = classes(type("com.minishop.user.CurrentUser").asRecord());

        assertGreen(ArchitectureTest.currentUser_isARecord, synthetic);
        assertGreen(ArchitectureTest.currentUser_isARecord, new ClassFileImporter().importClasses(CurrentUser.class));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRed(ArchRule rule, JavaClasses classes, String expectedViolator) {
        assertThatThrownBy(() -> rule.check(classes))
                .isInstanceOf(AssertionError.class).hasMessageContaining(expectedViolator);
    }

    private static void assertGreen(ArchRule rule, JavaClasses classes) {
        assertThatCode(() -> rule.check(classes)).doesNotThrowAnyException();
    }

    private ClassSpec type(String className) {
        return new ClassSpec(className);
    }

    /** Writes every spec as a class file under the temporary directory and imports exactly those files. */
    private JavaClasses classes(ClassSpec... specs) throws IOException {
        Path root = Files.createTempDirectory(classesDirectory, "classes");
        for (ClassSpec spec : specs) {
            Path file = root.resolve(spec.className.replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, spec.toBytes());
        }
        return new ClassFileImporter().importPath(root);
    }

    /** A class that has one field per referenced type (so each type becomes a dependency), plus optional parts. */
    private static final class ClassSpec {

        private final String className;
        private final List<String> fieldTypes = new ArrayList<>();
        private final List<String> annotations = new ArrayList<>();
        private final List<String> interfaces = new ArrayList<>();
        private String superclass = "java.lang.Object";
        private boolean isInterface;
        private boolean isRecord;

        private ClassSpec(String className) {
            this.className = className;
        }

        ClassSpec field(String type) {
            fieldTypes.add(type);
            return this;
        }

        ClassSpec annotation(String type) {
            annotations.add(type);
            return this;
        }

        ClassSpec implementing(String type) {
            interfaces.add(type);
            return this;
        }

        ClassSpec superclass(String type) {
            superclass = type;
            return this;
        }

        /** A record: final class, superclass java.lang.Record and a Record attribute with one component. */
        ClassSpec asRecord() {
            isRecord = true;
            superclass = "java.lang.Record";
            return this;
        }

        ClassSpec asInterface() {
            isInterface = true;
            return this;
        }

        byte[] toBytes() {
            return ClassFile.of().build(ClassDesc.of(className), builder -> {
                int flags = isInterface
                        ? AccessFlag.PUBLIC.mask() | AccessFlag.INTERFACE.mask() | AccessFlag.ABSTRACT.mask()
                        : AccessFlag.PUBLIC.mask();
                builder.withFlags(flags);
                builder.withSuperclass(ClassDesc.of(superclass));
                if (isRecord) {
                    builder.with(RecordAttribute.of(List.of(
                            RecordComponentInfo.of("id", ClassDesc.of("java.lang.Long")))));
                }
                builder.withInterfaceSymbols(interfaces.stream().map(ClassDesc::of).toList());
                if (!annotations.isEmpty()) {
                    builder.with(RuntimeVisibleAnnotationsAttribute.of(annotations.stream()
                            .map(type -> Annotation.of(ClassDesc.of(type))).toList()));
                }
                for (int i = 0; i < fieldTypes.size(); i++) {
                    int fieldFlags = isInterface
                            ? AccessFlag.PUBLIC.mask() | AccessFlag.STATIC.mask() | AccessFlag.FINAL.mask()
                            : AccessFlag.PRIVATE.mask();
                    builder.withField("f" + i, ClassDesc.of(fieldTypes.get(i)), fieldFlags);
                }
            });
        }
    }
}
