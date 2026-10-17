package dgm.kit.web;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemWriter;
import dgm.kit.route.RouteRule;
import dgm.kit.security.JwtFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Предел размера тела запроса (решение Р-29, ADR-021 пункт 5). Шлюз проверяет {@code Content-Length}, но вызов мимо шлюза и тело без
 * длины (chunked) он не покрывает, поэтому сервис ограничивает тело сам: объявленная длина больше предела сразу даёт 413, а при чтении
 * считаются фактические байты. Предел вебхуков меньше (64 КБ), его задаёт пометка маршрута.
 */
public final class BodyLimitFilter extends OncePerRequestFilter {

    private final long maxBody;
    private final long webhookMaxBody;

    public BodyLimitFilter(long maxBody, long webhookMaxBody) {
        if (maxBody <= 0 || webhookMaxBody <= 0) {
            throw new IllegalArgumentException("Предел размера тела должен быть положительным");
        }
        this.maxBody = maxBody;
        this.webhookMaxBody = webhookMaxBody;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        long shown = shownLimit(request, limit);
        if (request.getContentLengthLong() > limit) {
            write(request, response, RequestBodies.tooLarge(shown));
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequest(request, limit, shown), response);
        } catch (ProblemException e) {
            if (response.isCommitted()) {
                throw e;
            }
            write(request, response, e);
        }
    }

    private long limitFor(HttpServletRequest request) {
        if (request.getAttribute(JwtFilter.ROUTE_ATTRIBUTE) instanceof RouteRule rule && rule.webhook()) {
            return webhookMaxBody;
        }
        return RequestBodies.limitFor(request, maxBody);
    }

    /** Предел, который назван клиенту в сообщении: для формы multipart это предел файла, без запаса на служебные байты. */
    private long shownLimit(HttpServletRequest request, long limit) {
        return RequestBodies.isMultipart(request) && limit == maxBody + RequestBodies.MULTIPART_ENVELOPE ? maxBody : limit;
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, ProblemException e) throws IOException {
        ProblemWriter.write(request, response, ProblemWriter.problem(request, e.type(), e.getMessage(), e.extensions()), e.headers());
    }

    /** Запрос, поток тела которого прерывается ошибкой 413, когда прочитано больше предела. */
    static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;
        private final long shown;
        private ServletInputStream stream;

        LimitedBodyRequest(HttpServletRequest request, long limit, long shown) {
            super(request);
            this.limit = limit;
            this.shown = shown;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit, shown);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private final long shown;
        private long total;

        CountingStream(ServletInputStream delegate, long limit, long shown) {
            this.delegate = delegate;
            this.limit = limit;
            this.shown = shown;
        }

        private void count(long n) {
            if (n > 0) {
                total += n;
                if (total > limit) {
                    throw RequestBodies.tooLarge(shown);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int n = delegate.read(target, offset, length);
            count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
