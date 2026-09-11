package cn.teamone.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模块依赖纪律（架构设计 05 文档 §1.4 的程序化视图，CI 强制）。
 *
 * <p>R1 shared 纯净：零业务、不依赖 Spring 与任何兄弟模块
 * <br>R2 platform 只服务自身：不依赖业务域与装配层
 * <br>R3/R4/R5 prd ⊥ collab ⊥ eng：三业务域互相禁止编译期依赖（协作只走 Outbox 事件 + platform SPI）
 * <br>R6 insight 纯消费者：只依赖 platform(SPI)+shared，不反向依赖业务域
 * <br>R7 装配层只进不出：任何模块不得依赖 cn.teamone.app..（app 是组合根）</p>
 *
 * <p>包结构规则（api/app/domain/query/event/infra）随 M1-W2 prd 域填充后补充为分层约束。</p>
 *
 * @author Ivan Yang, 2026-09-11
 */
@AnalyzeClasses(packages = "cn.teamone", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] BIZ = {
            "cn.teamone.platform..", "cn.teamone.prd..", "cn.teamone.collab..",
            "cn.teamone.eng..", "cn.teamone.insight..", "cn.teamone.app.."
    };

    @ArchTest
    static final ArchRule R1_shared_is_pure = noClasses()
            .that().resideInAPackage("cn.teamone.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(BIZ)
            .orShould().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..");

    @ArchTest
    static final ArchRule R2_platform_standalone = noClasses()
            .that().resideInAPackage("cn.teamone.platform..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.teamone.prd..", "cn.teamone.collab..", "cn.teamone.eng..",
                    "cn.teamone.insight..", "cn.teamone.app..");

    @ArchTest
    static final ArchRule R3_prd_independent = noClasses()
            .that().resideInAPackage("cn.teamone.prd..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.teamone.collab..", "cn.teamone.eng..", "cn.teamone.app..");

    @ArchTest
    static final ArchRule R4_collab_independent = noClasses()
            .that().resideInAPackage("cn.teamone.collab..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.teamone.prd..", "cn.teamone.eng..", "cn.teamone.app..");

    @ArchTest
    static final ArchRule R5_eng_independent = noClasses()
            .that().resideInAPackage("cn.teamone.eng..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.teamone.prd..", "cn.teamone.collab..", "cn.teamone.app..");

    @ArchTest
    static final ArchRule R6_insight_read_only_consumer = noClasses()
            .that().resideInAPackage("cn.teamone.insight..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.teamone.prd..", "cn.teamone.collab..", "cn.teamone.eng..", "cn.teamone.app..");

    @ArchTest
    static final ArchRule R7_app_is_composition_root = noClasses()
            .that().resideOutsideOfPackage("cn.teamone.app..")
            .should().dependOnClassesThat().resideInAPackage("cn.teamone.app..");
}
