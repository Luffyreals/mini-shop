package com.minishop;

import com.minishop.catalog.ProductRepository;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** AC-001-15: quy tắc phụ thuộc giữa các tính năng được kiểm tự động, không cần Docker. */
@AnalyzeClasses(packages = "com.minishop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule catalog_dependsOnNoOtherFeature = noClasses().that().resideInAPackage("com.minishop.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.cart..", "com.minishop.order..", "com.minishop.user..", "com.minishop.admin..");

    @ArchTest
    static final ArchRule cart_dependsOnlyOnAllowedPackages = noClasses().that().resideInAPackage("com.minishop.cart..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.order..", "com.minishop.admin..", "com.minishop.config..", "com.minishop.storage..");

    /** QĐ5: việc gộp giỏ khi đăng nhập nằm trong cart, config không được biết tới cart hay order. */
    @ArchTest
    static final ArchRule config_doesNotDependOnCartOrOrder = noClasses().that().resideInAPackage("com.minishop.config..")
            .should().dependOnClassesThat().resideInAnyPackage("com.minishop.cart..", "com.minishop.order..");

    /** AC-004-21: giỏ hàng không bao giờ đụng tới tồn kho; chỉ đặt hàng (GĐ5) được trừ kho. */
    @ArchTest
    static final ArchRule cart_neverDecrementsStock = noClasses().that().resideInAPackage("com.minishop.cart..")
            .should().callMethod(ProductRepository.class, "decrementStock", Long.class, int.class);

    @ArchTest
    static final ArchRule user_dependsOnNoOtherFeature = noClasses().that().resideInAPackage("com.minishop.user..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.catalog..", "com.minishop.cart..", "com.minishop.order..", "com.minishop.admin..");

    @ArchTest
    static final ArchRule commonAndStorage_doNotDependOnFeatures = noClasses()
            .that().resideInAnyPackage("com.minishop.common..", "com.minishop.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.catalog..", "com.minishop.cart..", "com.minishop.order..",
                    "com.minishop.user..", "com.minishop.admin..");

    @ArchTest
    static final ArchRule nothingDependsOnAdmin = noClasses().that().resideOutsideOfPackage("com.minishop.admin..")
            .should().dependOnClassesThat().resideInAPackage("com.minishop.admin..");

    @ArchTest
    static final ArchRule featureSlices_haveNoCycles = slices().matching("com.minishop.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule controllers_doNotDependOnRepositories = noClasses()
            .that().areAnnotatedWith(Controller.class)
            .or().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class);

    /** Spec 002 §8: view chỉ nhận DTO, controller không đụng tới entity. */
    @ArchTest
    static final ArchRule controllers_doNotDependOnEntities = noClasses()
            .that().areAnnotatedWith(Controller.class)
            .or().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class);

    /** CLAUDE.md: admin chỉ là tầng web, gọi service của tính năng khác, không đụng repository. */
    @ArchTest
    static final ArchRule admin_doesNotUseRepositories = noClasses().that().resideInAPackage("com.minishop.admin..")
            .should().dependOnClassesThat().areAssignableTo(Repository.class);

    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith(Autowired.class);
}
