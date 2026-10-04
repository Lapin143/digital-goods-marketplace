// Минимальный маршрутизатор: шаблоны вида /payment/v1/payments/:id/refunds.
export function createRouter() {
  const routes = [];
  const compile = (pattern) => {
    const keys = [];
    const source = pattern
      .split('/')
      .map((part) => {
        if (part.startsWith(':')) {
          keys.push(part.slice(1));
          return '([^/]+)';
        }
        return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      })
      .join('/');
    return { re: new RegExp(`^${source}$`), keys };
  };
  return {
    add(method, pattern, handler) {
      routes.push({ method, pattern, ...compile(pattern), handler });
    },
    /** @returns {{handler, params}|{allowed:string[]}|null} */
    match(method, path) {
      const allowed = [];
      for (const r of routes) {
        const m = r.re.exec(path);
        if (!m) continue;
        if (r.method === method) {
          const params = {};
          r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
          return { handler: r.handler, params, pattern: r.pattern };
        }
        allowed.push(r.method);
      }
      return allowed.length ? { allowed } : null;
    },
  };
}
