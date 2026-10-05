package dgm.kit.boot.testing;

import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
 * ошибка видна в тесте, а не на стенде. Обратное (правило без обработчика) допустимо: сервис реализуется постепенно.
 */
public final class RouteCoverage {

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
