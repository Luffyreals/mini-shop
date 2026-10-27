package com.minishop;

import com.minishop.catalog.ProductRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** AC-001-15: quy tắc phụ thuộc giữa các tính năng được kiểm tự động, không cần Docker. */
@AnalyzeClasses(packages = "com.minishop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * GĐ6 (QĐ2), D3 (QĐ14): catalog được dùng storage (ảnh), common và desktop (cho màn hình của nó); không bao giờ
     * dùng cart (nó dùng {@code catalog.AddToCart} để cart cắm vào), order, user, admin, config.
     */
    @ArchTest
    static final ArchRule catalog_dependsOnlyOnStorageCommonAndDesktop = noClasses().that().resideInAPackage("com.minishop.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.cart..", "com.minishop.order..", "com.minishop.user..", "com.minishop.admin..",
                    "com.minishop.config..");

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

    /** GĐ5: order không phụ thuộc admin, config, storage. */
    @ArchTest
    static final ArchRule order_dependsOnlyOnAllowedPackages = noClasses().that().resideInAPackage("com.minishop.order..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.admin..", "com.minishop.config..", "com.minishop.storage..");

    /** QĐ1: order chỉ thấy API công khai hẹp của cart, không thấy CartService, CartRepository hay entity của cart. */
    @ArchTest
    static final ArchRule order_usesOnlyCartCheckoutApi = noClasses().that().resideInAPackage("com.minishop.order..")
            .should().dependOnClassesThat(resideInAPackage("com.minishop.cart..")
                    .and(not(simpleName("CartCheckout"))).and(not(simpleName("CheckoutLine"))));

    /**
     * Spec 006 §8, QĐ3: chỉ {@code order} trừ và cộng kho theo đơn hàng; ngay cả {@code catalog} (nơi định nghĩa
     * truy vấn) không được gọi, vì điều chỉnh của admin có câu riêng {@code adjustStock}.
     */
    @ArchTest
    static final ArchRule onlyOrderDecrementsOrRestoresStock = noClasses()
            .that().resideOutsideOfPackage("com.minishop.order..")
            .should().callMethod(ProductRepository.class, "decrementStock", Long.class, int.class)
            .orShould().callMethod(ProductRepository.class, "incrementStock", Long.class, int.class);

    /** Spec 006 BR-006-7, QĐ3: chỉ ProductAdminService gọi câu điều chỉnh kho của admin. */
    @ArchTest
    static final ArchRule onlyProductAdminServiceAdjustsStock = noClasses()
            .that().doNotHaveFullyQualifiedName("com.minishop.catalog.ProductAdminService")
            .should().callMethod(ProductRepository.class, "adjustStock", Long.class, int.class);

    /** Ngược lại với hai rule trên: không ai ngoài order được gọi, và order không được dùng câu của admin. */
    @ArchTest
    static final ArchRule order_neverAdjustsStockLikeAnAdmin = noClasses().that().resideInAPackage("com.minishop.order..")
            .should().callMethod(ProductRepository.class, "adjustStock", Long.class, int.class);

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

    /** GĐ6: admin chỉ được dùng catalog, order, storage, user và common (không cart, không config). */
    @ArchTest
    static final ArchRule admin_dependsOnlyOnAllowedFeatures = noClasses().that().resideInAPackage("com.minishop.admin..")
            .should().dependOnClassesThat().resideInAnyPackage("com.minishop.cart..", "com.minishop.config..");

    /** Spec 006 BR-006-15: admin không thấy entity (User, Category, Product đang public). */
    @ArchTest
    static final ArchRule admin_doesNotDependOnEntities = noClasses().that().resideInAPackage("com.minishop.admin..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class);

    /** GĐ6: chỉ catalog (ảnh sản phẩm) và admin (upload) dùng storage; ADR-0010. */
    @ArchTest
    static final ArchRule onlyCatalogAndAdminUseStorage = noClasses()
            .that().resideOutsideOfPackages("com.minishop.catalog..", "com.minishop.admin..", "com.minishop.storage..")
            .should().dependOnClassesThat().resideInAPackage("com.minishop.storage..");

    /** CLAUDE.md: admin chỉ là tầng web, gọi service của tính năng khác, không đụng repository. */
    @ArchTest
    static final ArchRule admin_doesNotUseRepositories = noClasses().that().resideInAPackage("com.minishop.admin..")
            .should().dependOnClassesThat().areAssignableTo(Repository.class);

    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith(Autowired.class);

    // ------------------------------------------------------------------ desktop (ADR-0014, spec 009)

    /**
     * Lớp giao diện của tính năng: {@code *Screen}, {@code *Drawer}, {@code *Dialog}, {@code *Presenter}, kể cả lớp
     * lồng và lớp ẩn danh của chúng (tên nhị phân {@code ProductScreen$1}).
     */
    private static final DescribedPredicate<JavaClass> UI_CLASSES = nameMatching(
            "UI classes (*Screen, *Drawer, *Dialog, *Presenter)", ".*(Screen|Drawer|Dialog|Presenter)(\\$.*)?");

    private static final DescribedPredicate<JavaClass> PRESENTERS = nameMatching(
            "presenters (*Presenter)", ".*Presenter(\\$.*)?");

    private static DescribedPredicate<JavaClass> nameMatching(String description, String regex) {
        return DescribedPredicate.describe(description, javaClass -> javaClass.getName().matches(regex));
    }

    /** Luật 1: ngoài {@code desktop} chỉ lớp giao diện được import {@code javafx..}. */
    @ArchTest
    static final ArchRule javafx_onlyInDesktopAndUiClasses = noClasses()
            .that().resideOutsideOfPackage("com.minishop.desktop..").and(not(UI_CLASSES))
            .should().dependOnClassesThat().resideInAPackage("javafx..");

    /**
     * Luật 2 (danh sách cho phép): từ {@code javafx..}, presenter chỉ được dùng {@code javafx.beans..} và
     * {@code javafx.collections..}. Mọi gói khác (scene, stage, concurrent, fxml, application, animation, geometry...)
     * đều bị cấm.
     */
    @ArchTest
    static final ArchRule presenters_useOnlyJavaFxBeansAndCollections = noClasses().that(PRESENTERS)
            .should().dependOnClassesThat(resideInAPackage("javafx..")
                    .and(not(resideInAnyPackage("javafx.beans..", "javafx.collections.."))))
            .allowEmptyShould(true);

    /** Tên nút giao diện: {@code *Screen}, {@code *Drawer}, {@code *Dialog} (kể cả lớp lồng, ẩn danh). */
    private static final DescribedPredicate<JavaClass> UI_NODE_CLASSES = nameMatching(
            "UI node classes (*Screen, *Drawer, *Dialog)", ".*(Screen|Drawer|Dialog)(\\$.*)?");

    /** AC-009-11: nút giao diện ngoài {@code desktop} không gọi {@code @Service}; chỉ presenter gọi, qua {@code BackgroundTasks}. */
    @ArchTest
    static final ArchRule uiNodes_doNotDependOnServices = noClasses()
            .that().resideOutsideOfPackage("com.minishop.desktop..").and(UI_NODE_CLASSES)
            .should().dependOnClassesThat().areAnnotatedWith(Service.class)
            .allowEmptyShould(true);

    /** Luật 3a: service không import JavaFX. */
    @ArchTest
    static final ArchRule services_doNotDependOnJavaFx = noClasses().that().areAnnotatedWith(Service.class)
            .should().dependOnClassesThat().resideInAPackage("javafx..");

    /** Luật 3b: entity không import JavaFX. */
    @ArchTest
    static final ArchRule entities_doNotDependOnJavaFx = noClasses().that().areAnnotatedWith(Entity.class)
            .should().dependOnClassesThat().resideInAPackage("javafx..");

    /** Luật 3c: repository không import JavaFX. */
    @ArchTest
    static final ArchRule repositories_doNotDependOnJavaFx = noClasses().that().areAssignableTo(Repository.class)
            .should().dependOnClassesThat().resideInAPackage("javafx..");

    /** Luật 3d: {@code common}, {@code config} và {@code storage} không import JavaFX. */
    @ArchTest
    static final ArchRule commonConfigAndStorage_doNotDependOnJavaFx = noClasses()
            .that().resideInAnyPackage("com.minishop.common..", "com.minishop.config..", "com.minishop.storage..")
            .should().dependOnClassesThat().resideInAPackage("javafx..");

    /** {@code common} là tầng dưới của mọi tính năng và không còn dính web (ADR-0014). */
    @ArchTest
    static final ArchRule common_doesNotDependOnSpringWeb = noClasses().that().resideInAPackage("com.minishop.common..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..");

    /** Luật 4 (QĐ4): {@code desktop} là tầng dưới, không bao giờ import một tính năng. */
    @ArchTest
    static final ArchRule desktop_dependsOnNoFeature = noClasses().that().resideInAPackage("com.minishop.desktop..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.minishop.catalog..", "com.minishop.cart..", "com.minishop.order..", "com.minishop.user..",
                    "com.minishop.admin..", "com.minishop.storage..", "com.minishop.config..");

    /** Luật 5: tầng dưới không biết {@code desktop}. */
    @ArchTest
    static final ArchRule commonAndConfig_doNotDependOnDesktop = noClasses()
            .that().resideInAnyPackage("com.minishop.common..", "com.minishop.config..")
            .should().dependOnClassesThat().resideInAPackage("com.minishop.desktop..");

    /** Luật 6: màn hình không đụng repository hay entity; gọi service qua {@code BackgroundTasks}. */
    @ArchTest
    static final ArchRule uiClasses_doNotUseRepositoriesOrEntities = noClasses().that(UI_CLASSES)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .orShould().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .allowEmptyShould(true);

    /** Luật 7 (AC-009-11): việc nền chỉ đi qua {@code BackgroundTasks}, nên ngoài {@code desktop} không dùng {@code javafx.concurrent}. */
    @ArchTest
    static final ArchRule javafxConcurrent_onlyInDesktop = noClasses()
            .that().resideOutsideOfPackage("com.minishop.desktop..")
            .should().dependOnClassesThat().resideInAPackage("javafx.concurrent..");

    /**
     * Luật D2-1 (QĐ15, AC-009-11): service, entity và repository không biết {@code desktop}. Service không biết giao
     * diện; chỉ presenter gọi service, qua {@code BackgroundTasks}.
     */
    @ArchTest
    static final ArchRule servicesEntitiesAndRepositories_doNotDependOnDesktop = noClasses()
            .that().areAnnotatedWith(Service.class)
            .or().areAnnotatedWith(Entity.class)
            .or().areAssignableTo(Repository.class)
            .should().dependOnClassesThat().resideInAPackage("com.minishop.desktop..");

    /**
     * Luật D2-2 (QĐ15, AC-009-6): chỉ có một đường đăng nhập. Ngoài {@code com.minishop.user..}, không ai gọi
     * {@code matches} của một {@code PasswordEncoder} (kể cả qua kiểu cụ thể như {@code BCryptPasswordEncoder}).
     * {@code config} vẫn được gọi {@code encode} để seed.
     */
    @ArchTest
    static final ArchRule onlyUserChecksPasswords = noClasses()
            .that().resideOutsideOfPackage("com.minishop.user..")
            .should().callMethodWhere(target(name("matches")).and(target(owner(assignableTo(PasswordEncoder.class)))));

    /** ADR-0014: {@code CurrentUser} là record thuần giá trị (không implements {@code UserDetails}). */
    @ArchTest
    static final ArchRule currentUser_isARecord = classes()
            .that().haveFullyQualifiedName("com.minishop.user.CurrentUser")
            .should().beRecords();
}

