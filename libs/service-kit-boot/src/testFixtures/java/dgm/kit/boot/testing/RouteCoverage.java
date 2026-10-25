package dgm.kit.boot.testing;

import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Сверка кода сервиса с правилами маршрутов ({@code dgm/routes.json}, их создаёт tools/docs-checks/gen_routes.py из OpenAPI).
 * Фильтр каркаса отвечает 404 на маршрут, которого нет в правилах, поэтому обработчик без правила никогда не получит запрос:
 * ошибка видна в тесте, а не на стенде. Обратное (правило без обработчика) допустимо, но только если операция записана в реестре
 * готовности как ещё не реализованная ({@link #ledgerProblems}): сервис реализуется постепенно, а пропуск не бывает случайным.
 */
public final class RouteCoverage {

    /** Статусы реестра операций: обработчик есть; войдёт в срез 1; войдёт в срез 2; войдёт в срез 3; относится к Ф5. */
    public static final String DONE = "done";
    public static final String SLICE = "slice1";
    public static final String SLICE2 = "slice2";
    public static final String SLICE3 = "slice3";
    public static final String LATER = "later";
    private static final Set<String> STATUSES = Set.of(DONE, SLICE, SLICE2, SLICE3, LATER);

    private RouteCoverage() {
    }

    /** Один обработчик контроллера: метод HTTP и шаблон пути. */
    public record Handler(String method, String path, String where) {
        @Override
        public String toString() {
            return method + " " + path + " (" + where + ")";
        }
    }

    /** Обработчики всех {@code @RestController} пакета. */
    public static List<Handler> handlers(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Handler> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = load(definition.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            List<String> prefixes = base == null || base.path().length == 0 ? List.of("") : List.of(base.path());
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                List<String> paths = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
                List<String> verbs = mapping.method().length == 0 ? List.of("GET") : java.util.Arrays.stream(mapping.method()).map(RequestMethod::name).toList();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        for (String verb : verbs) {
                            result.add(new Handler(verb, prefix + path, type.getSimpleName() + "." + method.getName()));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Обработчики, для которых в файле правил нет маршрута. Пусто, если всё сходится. */
    public static List<String> handlersWithoutRule(String basePackage, String routesResource) {
        RoutePolicy policy = RoutePolicy.fromClasspath(routesResource);
        List<String> missing = new ArrayList<>();
        for (Handler handler : handlers(basePackage)) {
            String sample = handler.path().replaceAll("\\{[^}/]+}", "x");
            if (policy.match(handler.method(), sample).isEmpty()) {
                missing.add(handler.toString());
            }
        }
        return missing;
    }

    /**
     * Полнота операций (находка 5 плана Ф4). Сверяет три вещи: операции из OpenAPI (файл правил маршрутов), обработчики контроллеров и реестр
     * готовности {@code ledgerResource}, где каждая операция записана строкой «имя статус» ({@code done}, {@code slice1}, {@code slice2}, {@code slice3}, {@code later};
     * после {@code #} комментарий). Красным запуск становится, если
     * <ul>
     * <li>операция есть в OpenAPI и нет в реестре, или наоборот: новая операция не может остаться без решения «когда»;</li>
     * <li>статус {@code done}, а обработчика нет: операция заявлена, но не реализована;</li>
     * <li>обработчик есть, а статус не {@code done}: реестр отстал от кода, статус меняется в том же commit, где появился обработчик.</li>
     * </ul>
     * Пусто, если всё сходится.
     */
    public static List<String> ledgerProblems(String basePackage, String routesResource, String ledgerResource) {
        RoutePolicy policy = RoutePolicy.fromClasspath(routesResource);
        Set<String> implemented = new TreeSet<>();
        for (Handler handler : handlers(basePackage)) {
            String sample = handler.path().replaceAll("\\{[^}/]+}", "x");
            policy.match(handler.method(), sample).ifPresent(rule -> implemented.add(rule.operationId()));
        }
        List<String> problems = new ArrayList<>();
        Map<String, String> ledger = readLedger(ledgerResource, problems);
        Set<String> declared = new TreeSet<>();
        for (RouteRule rule : policy.rules()) {
            declared.add(rule.operationId());
        }
        for (String id : declared) {
            if (!ledger.containsKey(id)) {
                problems.add("Операция " + id + " есть в OpenAPI и не записана в реестре " + ledgerResource);
            }
        }
        for (Map.Entry<String, String> entry : ledger.entrySet()) {
            String id = entry.getKey();
            String status = entry.getValue();
            if (!declared.contains(id)) {
                problems.add("Операции " + id + " нет в OpenAPI, уберите её из реестра " + ledgerResource);
                continue;
            }
            boolean has = implemented.contains(id);
            if (DONE.equals(status) && !has) {
                problems.add("Операция " + id + " записана как готовая (done), а обработчика в контроллерах нет");
            }
            if (!DONE.equals(status) && has) {
                problems.add("У операции " + id + " есть обработчик, а в реестре статус " + status + ": переведите её в done");
            }
        }
        return problems;
    }

    /** Реестр готовности: имя операции и статус. Ошибки разбора добавляются в {@code problems}. */
    private static Map<String, String> readLedger(String resource, List<String> problems) {
        Map<String, String> ledger = new LinkedHashMap<>();
        String text;
        try (InputStream in = RouteCoverage.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                problems.add("Нет реестра операций " + resource);
                return ledger;
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Реестр операций не читается: " + resource, e);
        }
        for (String line : text.split("\\R")) {
            int hash = line.indexOf('#');
            String content = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (content.isEmpty()) {
                continue;
            }
            String[] parts = content.split("\\s+");
            if (parts.length != 2) {
                problems.add("Строка реестра «" + content + "»: нужны имя операции и статус");
            } else if (!STATUSES.contains(parts[1])) {
                problems.add("Операция " + parts[0] + ": неизвестный статус «" + parts[1] + "», допустимы " + new TreeSet<>(STATUSES));
            } else if (ledger.putIfAbsent(parts[0], parts[1]) != null) {
                problems.add("Операция " + parts[0] + " записана в реестре дважды");
            }
        }
        return ledger;
    }

    /**
     * Внутренняя согласованность правил: внутренние маршруты ({@code /internal/}) закрыты списком вызывающих и не принимают токен
     * пользователя, пользовательские ({@code /api/}) списка вызывающих не имеют. Пусто, если всё сходится.
     */
    public static List<String> policyProblems(String routesResource, String expectedService) {
        RoutePolicy policy = RoutePolicy.fromClasspath(routesResource);
        List<String> problems = new ArrayList<>();
        if (!expectedService.equals(policy.service())) {
            problems.add("В правилах указан сервис " + policy.service() + ", ожидался " + expectedService);
        }
        for (RouteRule rule : policy.rules()) {
            String name = rule.method() + " " + rule.path();
            boolean internal = rule.path().startsWith("/internal/");
            if (internal && rule.callers().isEmpty()) {
                problems.add(name + ": внутренний маршрут без списка вызывающих");
            }
            if (internal && rule.requiresToken()) {
                problems.add(name + ": внутренний маршрут требует токен пользователя");
            }
            if (!internal && !rule.path().startsWith("/api/")) {
                problems.add(name + ": путь не начинается с /api/ или /internal/");
            }
            if (!internal && !rule.callers().isEmpty()) {
                problems.add(name + ": пользовательский маршрут со списком вызывающих");
            }
        }
        return problems;
    }

    private static Class<?> load(String className) {
        try {
            return ClassUtils.forName(className, RouteCoverage.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("Класс контроллера не загружается: " + className, e);
        }
    }
}
