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
 * <br>R7 装配层只进不出：任何模块不得依赖 cn.teamone.app..（app 是组合根）
 * <br>R8 Git 进程唯一出口：除 cn.teamone.eng.infra.git..（GitCommandPort，07 §2.3 唯一出墙口）外，
 * 任何 cn.teamone 类不得 new ProcessBuilder——fork 子进程必须经 GitPort 抽象（参数白名单/超时强杀）</p>
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

    /**
     * R8（M2-INC-1 W1 清账，07 §2.3 纪律的程序化视图；M4-INC1 修订）：<b>git</b> 子进程只能从
     * cn.teamone.eng.infra.git..（GitCommandPort：参数白名单 + 无 shell + 超时强杀）拉起。
     * M4-INC1 起允许第二个进程面：cn.teamone.eng.runner..（内嵌 Runner 的构建工具子进程——
     * mvn/npm 及 which 探测，命令来自服务端 PipelineJobTemplates 模板，无 shell 无用户输入）。
     * 覆盖 ProcessBuilder 两个构造器：String...（编译为 String[]）与 List。
     */
    @ArchTest
    static final ArchRule R8_process_only_via_git_infra = noClasses()
            .that().resideOutsideOfPackage("cn.teamone.eng.infra.git..")
            .and().resideOutsideOfPackage("cn.teamone.eng.runner..")
            .should().callConstructor(ProcessBuilder.class, String[].class)
            .orShould().callConstructor(ProcessBuilder.class, java.util.List.class);
}
