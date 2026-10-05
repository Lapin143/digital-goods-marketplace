package dgm.kit.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dgm.kit.events.EventHandler;
import dgm.kit.events.ProcessedEvents;
import dgm.kit.secret.SecretValue;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Архитектурные правила для всех сервисов (c4-components.md, раздел 3; decomposition.md, раздел 7; conventions.md, раздел 5).
 *
 * <p>Сервис подключает их одним полем в своём тесте:
 * <pre>{@code
 * @AnalyzeClasses(packages = "dgm.order", importOptions = ImportOption.DoNotIncludeTests.class)
 * class OrderArchitectureTest {
 *     @ArchTest
 *     static final ArchTests RULES = ArchTests.in(ServiceArchRules.class);
 * }
 * }</pre>
 * Правила лежат в тестовых фикстурах каркаса и в рабочий jar не попадают. Каждое правило проверяется на нарушении тестом
 * каркаса (ServiceArchRulesTest): правило, которое ничего не ловит, не защищает.
 *
 * <p>Правила, зависящие от компонентов, которых ещё нет (охранник идемпотентности, слушатель параметров, запись аудита),
 * появятся в Ф4 вместе с этими компонентами.
 */
public final class ServiceArchRules {

    private ServiceArchRules() {
    }

    /** Пакеты, где разрешено читать значение {@link SecretValue}: каркас, модули шифрования, клиенты, сборка писем (c4-components.md, раздел 2). */
    static final String[] SECRET_REVEAL_PACKAGES = {"dgm.kit.secret..", "dgm.kit.tls..", "dgm.kit.boot..", "..crypto..", "..client..", "..letter.."};

    private static final Set<String> TIME_TYPES = Set.of("java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.LocalTime", "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Year",
            "java.time.YearMonth", "java.time.MonthDay");

    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    // 1. Часы только внедряемые (conventions.md, раздел 5; test-strategy.md, «Часы»)
    @ArchTest
    public static final ArchRule noDirectSystemClock = lenient(classes().that().resideOutsideOfPackage("dgm.kit.time..")
            .should(notReadSystemClock())
            .because("время берётся только из внедряемого java.time.Clock, иначе тесты не могут управлять временем"));

    // 2. Секреты и журнал
    @ArchTest
    public static final ArchRule secretValueRevealedOnlyWhereAllowed = lenient(noClasses().that().resideOutsideOfPackages(SECRET_REVEAL_PACKAGES)
            .should().callMethod(SecretValue.class, "reveal")
            .orShould().callMethod(SecretValue.class, "revealBytes")
            .because("значение секрета читается только в модулях шифрования, TLS и клиентах, где ревьюер видит это сразу"));

    @ArchTest
    public static final ArchRule noStandardStreams = lenient(NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("вывод только через журнал со структурой и маскированием (conventions.md, раздел 13)"));

    @ArchTest
    public static final ArchRule noJavaUtilLogging = lenient(NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
            .because("журнал один, SLF4J (conventions.md, раздел 13)"));

    // 3. Публикация событий только через Outbox (ADR-005)
    @ArchTest
    public static final ArchRule noDirectKafkaProducer = lenient(noClasses().that().resideOutsideOfPackage("dgm.kit..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka.clients.producer..", "org.springframework.kafka..")
            .because("события публикуются только записью в Outbox в той же транзакции (ADR-005)"));

    // 4. Обработчик события не обходит обёртку потребителя (ADR-006)
    @ArchTest
    public static final ArchRule eventHandlersDoNotBypassConsumer = lenient(noClasses().that().implement(EventHandler.class)
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka..")
            .orShould().dependOnClassesThat().areAssignableTo(ProcessedEvents.class)
            .because("processed_event, повторы и DLQ делает обёртка потребителя, обработчик содержит только действие (ADR-006)"));

    // 5. Слои (c4-components.md, раздел 3, правила 1, 5, 6)
    @ArchTest
    public static final ArchRule transactionsNotInControllersClientsOrchestrators = lenient(noClasses()
            .that(simpleNameEndsWithAny("Controller", "Client", "Orchestrator"))
            .should().beAnnotatedWith(TRANSACTIONAL)
            .because("транзакция открывается только в доменном и сервисном слое"));

    @ArchTest
    public static final ArchRule transactionalMethodsNotInControllersClientsOrchestrators = lenient(noMethods()
            .that().areDeclaredInClassesThat(simpleNameEndsWithAny("Controller", "Client", "Orchestrator"))
            .should().beAnnotatedWith(TRANSACTIONAL)
            .because("транзакция открывается только в доменном и сервисном слое"));

    @ArchTest
    public static final ArchRule controllersDoNotUseRepositoriesOrClients = lenient(noClasses().that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Client")
            .because("контроллер вызывает только сервисный и доменный слой"));

    @ArchTest
    public static final ArchRule repositoriesDoNotCallClients = lenient(noClasses().that().haveSimpleNameEndingWith("Repository")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Client")
            .because("клиент соседа вызывает оркестратор или сервисный слой, не репозиторий"));

    @ArchTest
    public static final ArchRule domainMakesNoNetworkCalls = lenient(noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net..", "org.springframework.web.client..",
                    "org.springframework.web.reactive..", "org.apache.kafka..", "io.lettuce..", "org.springframework.data.redis..")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Client")
            .because("доменный слой не делает сетевых вызовов"));

    // 6. Границы модулей (decomposition.md, раздел 7, правила 1 и 3)
    @ArchTest
    public static final ArchRule modulesAreReachedOnlyThroughApi = lenient(classes().that().resideInAPackage("dgm..")
            .and().resideOutsideOfPackage("dgm.kit..")
            .should(respectModuleBoundaries())
            .because("модуль вызывает соседа только через интерфейс и DTO в подпакете api, репозитории и сущности соседа закрыты"));

    // 7. Нет состояния сессии в сервисе (NFT-1.3)
    @ArchTest
    public static final ArchRule noHttpSessions = lenient(noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.servlet.http.HttpSession")
            .orShould().beAnnotatedWith("org.springframework.web.context.annotation.SessionScope")
            .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.SessionAttributes")
            .because("сервис без состояния: сессия пользователя это токен, два экземпляра равнозначны (NFT-1.3)"));

    @ArchTest
    public static final ArchRule noMutableStaticFields = lenient(fields().that().areStatic().should().beFinal()
            .because("изменяемого статического состояния нет: экземпляры сервиса равнозначны (NFT-1.3)"));

    /** Правило без совпадений считается выполненным: у каркаса, например, нет контроллеров, а правило общее для всех. */
    private static ArchRule lenient(ArchRule rule) {
        return rule.allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> simpleNameEndsWithAny(String... suffixes) {
        return new DescribedPredicate<>("have a simple name ending with one of " + Arrays.toString(suffixes)) {
            @Override
            public boolean test(JavaClass input) {
                for (String suffix : suffixes) {
                    if (input.getSimpleName().endsWith(suffix)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static ArchCondition<JavaClass> notReadSystemClock() {
        return new ArchCondition<>("not read the system clock directly") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    if (readsSystemClock(call)) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
                for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                    if (call.getTargetOwner().getName().equals("java.util.Date") && call.getTarget().getRawParameterTypes().isEmpty()) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
            }
        };
    }

    static boolean readsSystemClock(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        List<String> params = call.getTarget().getRawParameterTypes().stream().map(JavaClass::getName).toList();
        if (name.equals("now") && TIME_TYPES.contains(owner)) {
            return !params.contains("java.time.Clock");
        }
        if (owner.equals("java.lang.System") && name.equals("currentTimeMillis")) {
            return true;
        }
        return owner.equals("java.time.Clock") && (name.equals("systemUTC") || name.equals("systemDefaultZone") || name.equals("system"));
    }

    private static ArchCondition<JavaClass> respectModuleBoundaries() {
        return new ArchCondition<>("use other modules only through their api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = module(origin.getPackageName());
                if (from == null) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String to = module(target.getPackageName());
                    if (to == null || to.equals(from)) {
                        continue;
                    }
                    String targetPackage = target.getPackageName();
                    boolean api = targetPackage.equals(to + ".api") || targetPackage.startsWith(to + ".api.");
                    if (!api) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Модуль по имени пакета: {@code dgm.<сервис>.<модуль>}; у пакета короче трёх частей модуля нет. */
    static String module(String packageName) {
        String[] parts = packageName.split("\\.");
        if (parts.length < 3 || !parts[0].equals("dgm") || parts[1].equals("kit")) {
            return null;
        }
        return parts[0] + "." + parts[1] + "." + parts[2];
    }
}
