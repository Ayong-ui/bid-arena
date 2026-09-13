package com.bidarena.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

/**
 * 架构边界的可执行守卫（{@code DESIGN.md} §2.2、§2.4）。
 *
 * <p>为什么要有这个测试：包结构与依赖方向是最容易随时间腐化的东西——它是"约定"，
 * 而约定会被一次"顺手 import 一下"破坏，且不会让任何功能测试变红。写在这里的每条规则
 * 都对应设计文档里的一句约束，破坏时 {@code mvn test} 直接失败。
 *
 * <p>规则维护约定：先改设计文档，再改这里的规则。**不允许为了让它变绿而放宽规则**——
 * 如果某条规则不成立，那是一个待修的架构问题，应当记在 {@code docs/STATUS.md} 里，
 * 而不是把断言删掉（详见 {@code CONTRIBUTING.md} §4）。
 *
 * <p>{@code ImportOption.DoNotIncludeTests} 让分析只看生产代码：测试大量引用各层来
 * 构造场景，把它们算进来会让规则失去意义。
 */
@AnalyzeClasses(packages = "com.bidarena", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] LAYERS = {
        "..application..", "..adapter..", "..persistence..", "com.bidarena.bootstrap..", "com.bidarena.api..",
    };

    private static final String[] FRAMEWORKS = {
        "org.noear..", "com.fasterxml.jackson..", "org.slf4j..", "java.sql..", "javax.sql..",
    };

    /** “上下文”切片：不属于任何上下文（共享内核、api、bootstrap）的类被忽略。 */
    private static final SliceAssignment CONTEXTS_BY_PACKAGE = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String context = contextOf(javaClass.getPackageName());
            return context == null ? SliceIdentifier.ignore() : SliceIdentifier.of(context);
        }

        @Override
        public String getDescription() {
            return "限界上下文";
        }
    };

    /**
     * 领域层只依赖 JDK 与共享内核。
     *
     * <p>这是所有依赖规则里最硬的一条：领域模型一旦 import 了框架或 JDBC，业务规则就再也
     * 不能脱离运行环境被理解与验证，而 {@code DESIGN.md} §2.4 正是把"不变量写在领域里"
     * 当作可测试化的前提。
     */
    @ArchTest
    static final ArchRule domainDependsOnlyOnItselfAndTheSharedKernel =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(LAYERS)
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAnyPackage(FRAMEWORKS)
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("com.bidarena.shared.Db")
                    .because("领域层不得依赖应用层/适配层/框架/JDBC（DESIGN.md §2.2）");

    /**
     * 应用层不碰入站适配器与启动装配，也不直接依赖框架。
     *
     * <p>允许依赖 {@code persistence}：事务边界必须在应用层，而事务里的每一条 SQL 都写在
     * 仓储里，两者由同一个 {@code Connection} 串起来——这层依赖是刻意的（见 DECISIONS D-24）。
     * 反过来（入站适配器、启动装配、HTTP 封套工具）没有任何理由进应用层。
     */
    @ArchTest
    static final ArchRule applicationLayerDoesNotDependOnInboundAdaptersOrBootstrap =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..adapter..", "com.bidarena.bootstrap..", "com.bidarena.api..")
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.noear..", "com.fasterxml.jackson..")
                    .because("应用层不得依赖入站适配器、启动装配与 HTTP 封套，也不得直接用框架类型"
                            + "（DESIGN.md §2.2）");

    /**
     * 出站适配器（JDBC 仓储）不反向依赖应用层与入站适配器。
     *
     * <p>这条规则是"拆出 persistence 包"的原因与结果：仓储与应用层曾经同在 adapter 包，
     * 于是"控制器 -> 应用服务 -> 仓储 -> 控制器"形成了包级循环，循环又让"任意两个包之间
     * 不得存在循环依赖"这条设计约束不可能成立。方向固定为 入站 -> 应用 -> 出站 之后，
     * 循环消失，规则可以真正生效。
     */
    @ArchTest
    static final ArchRule persistenceLayerDoesNotDependOnApplicationOrAdapters =
            noClasses()
                    .that()
                    .resideInAPackage("..persistence..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..application..", "..adapter..", "com.bidarena.bootstrap..",
                            "com.bidarena.api..")
                    .because("出站适配器只实现持久化，不得反向依赖用例与入站适配器（DESIGN.md §2.2）");

    /** 入站适配器不介入装配：需要什么由组合根注入，而不是自己去 new 或读配置。 */
    @ArchTest
    static final ArchRule inboundAdaptersDoNotDependOnBootstrap =
            noClasses()
                    .that()
                    .resideInAPackage("..adapter..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("com.bidarena.bootstrap..")
                    .because("装配只发生在 bootstrap，适配器不得依赖它（DESIGN.md §2.2）");

    /** 共享内核不依赖任何上下文：它是被依赖方，不是依赖方。 */
    @ArchTest
    static final ArchRule sharedKernelDoesNotDependOnContexts =
            noClasses()
                    .that()
                    .resideInAPackage("com.bidarena.shared..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("com.bidarena.identity..", "com.bidarena.wallet..",
                            "com.bidarena.auction..", "com.bidarena.agentaccess..", "com.bidarena.api..",
                            "com.bidarena.bootstrap..")
                    .because("共享内核必须可以被任何上下文依赖而不产生反向依赖（DESIGN.md §2.3）");

    /**
     * 不同上下文的领域模型之间不得直接引用。
     *
     * <p>跨上下文只允许走对方的应用服务或领域端口。
     *
     * <p>注意这里用的是 {@code classes().should(condition)} 而不是
     * {@code noClasses().should(condition)}：后者会把自定义条件交给 ArchUnit 的
     * {@code never(...)} 反转，“条件里没报违规”会被当成失败、而“条件报了违规”反而通过，
     * 于是规则永远不会变红（这个坑由变异测试 A6 暴露，见 DEBUG_LOG DBG-19）。
     *
     * <p>判断按“是否同一个上下文”而不是包名列表面：P5 加 {@code agentaccess} 时自动生效，
     * 不会因为忘了改模式而静默失去守护。
     */
    @ArchTest
    static final ArchRule domainModelsOfDifferentContextsDoNotDependOnEachOther =
            classes().that()
                    .resideInAPackage("..domain..")
                    .should(new ArchCondition<JavaClass>("只依赖本上下文的领域模型") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            forEachForeignContextDependency(item, ".domain", events);
                        }
                    });

    /** 同理：不同上下文的入站适配器之间不得互相引用（适配器之间禁止直接引用）。 */
    @ArchTest
    static final ArchRule adaptersOfDifferentContextsDoNotDependOnEachOther =
            classes().that()
                    .resideInAPackage("..adapter..")
                    .should(new ArchCondition<JavaClass>("只依赖本上下文与共享内核") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            forEachForeignContextDependency(item, ".adapter", events);
                        }
                    });

    /**
     * 上下文之间不得相互依赖成环。
     *
     * <p>切片只取“限界上下文”四个包，不用 {@code com.bidarena.(*)..} 通配：
     * {@code com.bidarena.api} 是横切的入站基础设施（封套、过滤器、当前用户），不属于任何上下文。
     * 它同时依赖 identity（验证令牌需要 TokenService/Principal）又被每个控制器依赖，
     * 是设计使然；把它当成一个“上下文”来查环，只会得到一个假阳性。
     *
     * <p>用 {@link SliceAssignment} 而不是正则捕获：上下文列表集中在下方的 {@link #contextOf}，
     * P5 新增 {@code agentaccess} 时自动纳入，不会因为忘了改模式而静默失去守护。
     */
    @ArchTest
    static final ArchRule contextsAreFreeOfCycles =
            slices().assignedFrom(CONTEXTS_BY_PACKAGE)
                    .should()
                    .beFreeOfCycles()
                    .because("上下文之间不得相互依赖成环（DESIGN.md §2.4）");

    /**
     * 包级无循环依赖。
     *
     * <p>按“上下文.层”切片：只按上下文切片看不出层与层绕回去（入站 -> 应用 -> 出站 应当是
     * 一条直线），而层与层的回环正是拆出 {@code persistence} 包要解决的问题。
     */
    @ArchTest
    static final ArchRule layersAreFreeOfCycles =
            slices().matching("com.bidarena.(*).(*)..")
                    .should()
                    .beFreeOfCycles()
                    .because("任意两个包之间不得存在循环依赖（DESIGN.md §2.4）");

    /**
     * 判断一条依赖是否跨到了"另一个上下文"的某个层。
     *
     * <p>上下文 = {@code com.bidarena.<context>}，共享内核（{@code com.bidarena.shared}）、
     * HTTP 封套（{@code com.bidarena.api}）与 {@code bootstrap} 不属于任何上下文，
     * 它们是否可被依赖由上方的规则分别约束。
     */
    private static void forEachForeignContextDependency(JavaClass item, String layerSuffix,
            ConditionEvents events) {
        String ownContext = contextOf(item.getPackageName());
        if (ownContext == null) {
            return;
        }
        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
            String targetPackage = dependency.getTargetClass().getPackageName();
            String targetContext = contextOf(targetPackage);
            if (targetContext == null || targetContext.equals(ownContext)) {
                continue;
            }
            if (!targetPackage.contains(layerSuffix)) {
                continue;
            }
            events.add(SimpleConditionEvent.violated(item,
                    item.getName() + " -> " + dependency.getTargetClass().getName()
                            + "（跨上下文直接引用，应改走对方的应用服务或端口）"));
        }
    }

    /** {@code com.bidarena.auction.application} -> {@code auction}；不属于任何上下文时返回 null。 */
    private static String contextOf(String packageName) {
        String[] segments = packageName.split("\\.");
        if (segments.length < 3 || !"com".equals(segments[0]) || !"bidarena".equals(segments[1])) {
            return null;
        }
        return switch (segments[2]) {
            case "identity", "wallet", "auction", "agentaccess" -> segments[2];
            default -> null;
        };
    }
}
