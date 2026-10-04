package dgm.kit.problem;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ответы об ошибке в одном формате (компонент {@code trace-filter}, часть про {@code application/problem+json}).
 *
 * <p>Любое исключение контроллера становится проблемой из реестра conventions.md, раздел 9.1. Подробности непредвиденной ошибки
 * (трассировка, SQL, адреса) в ответ не попадают: клиент получает общий текст и идентификатор корреляции, а полная причина
 * остаётся в журнале сервиса под тем же идентификатором.
 */
@RestControllerAdvice
public final class ProblemAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);
    private static final MediaType PROBLEM_JSON = new MediaType("application", "problem+json", StandardCharsets.UTF_8);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception e, HttpServletRequest request) {
        ProblemType type;
        String detail;
        Map<String, Object> extensions = Map.of();
        Map<String, String> headers = Map.of();
        if (e instanceof ProblemException pe) {
            type = pe.type();
            detail = pe.getMessage();
            extensions = pe.extensions();
            headers = pe.headers();
        } else if (e instanceof MethodArgumentNotValidException invalid) {
            type = ProblemType.VALIDATION_FAILED;
            detail = "Данные не прошли проверку, подробности в поле errors.";
            extensions = Map.of("errors", fieldErrors(invalid));
        } else if (e instanceof HttpMessageNotReadableException || e instanceof TypeMismatchException) {
            type = ProblemType.BAD_REQUEST;
            detail = "Тело или параметры запроса не разбираются.";
        } else if (e instanceof HttpMediaTypeNotSupportedException) {
            type = ProblemType.UNSUPPORTED_MEDIA_TYPE;
            detail = "Тип содержимого запроса не поддерживается, нужен application/json.";
        } else if (e instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            if (status == 404 || status == 405) {
                type = ProblemType.NOT_FOUND;
                detail = "Маршрут не найден.";
            } else if (status >= 400 && status < 500) {
                type = ProblemType.BAD_REQUEST;
                detail = "Запрос не принят.";
            } else {
                type = ProblemType.INTERNAL_ERROR;
                detail = "Внутренняя ошибка. Повторите запрос позже, при повторе сообщите идентификатор запроса.";
            }
        } else {
            LOG.error("Непредвиденная ошибка при обработке запроса {} {}", request.getMethod(), request.getRequestURI(), e);
            type = ProblemType.INTERNAL_ERROR;
            detail = "Внутренняя ошибка. Повторите запрос позже, при повторе сообщите идентификатор запроса.";
        }
        Problem problem = ProblemWriter.problem(request, type, detail, extensions);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(type.status()).contentType(PROBLEM_JSON).header("Cache-Control", "no-store");
        headers.forEach(builder::header);
        return builder.body(problem.toJson());
    }

    private static List<Map<String, Object>> fieldErrors(MethodArgumentNotValidException e) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pointer", "/" + fe.getField().replace('.', '/'));
            item.put("code", fieldCode(fe.getCode()));
            item.put("detail", "Значение поля не принято.");
            errors.add(item);
        }
        return errors;
    }

    /** Код ошибки поля из набора conventions.md, раздел 9: required, invalid_format, out_of_range, too_long, too_short. */
    static String fieldCode(String constraint) {
        if (constraint == null) {
            return "invalid_format";
        }
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "required";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Range" -> "out_of_range";
            case "Size", "Length" -> "too_long";
            default -> "invalid_format";
        };
    }
}
