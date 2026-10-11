// Router minimal: rute cu parametri (/produs/:slug), handler-e async care primesc ctx.

export class Router {
  constructor() { this.routes = []; }

  add(method, pattern, ...handlers) {
    const keys = [];
    const re = new RegExp(`^${pattern.replace(/\/$/, '').replace(/[.+?^${}()|[\]\\]/g, '\\$&')
      .replace(/\\\(([^)]*)\\\)/g, '($1)')
      .replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; })}/?$`);
    this.routes.push({ method, re, keys, handlers });
    return this;
  }

  get(p, ...h) { return this.add('GET', p, ...h); }
  post(p, ...h) { return this.add('POST', p, ...h); }
  put(p, ...h) { return this.add('PUT', p, ...h); }
  patch(p, ...h) { return this.add('PATCH', p, ...h); }
  delete(p, ...h) { return this.add('DELETE', p, ...h); }

  match(method, path) {
    let allowed = false;
    for (const r of this.routes) {
      const m = r.re.exec(path);
      if (!m) continue;
      if (r.method !== method && !(method === 'HEAD' && r.method === 'GET')) { allowed = true; continue; }
      const params = {};
      r.keys.forEach((k, i) => { try { params[k] = decodeURIComponent(m[i + 1]); } catch { params[k] = m[i + 1]; } });
      return { handlers: r.handlers, params };
    }
    return allowed ? { methodNotAllowed: true } : null;
  }
}

/** rulează lanțul de handler-e; un handler poate returna `false` ca să oprească lanțul (gardă) */
export async function runHandlers(handlers, ctx) {
  for (const h of handlers) {
    const r = await h(ctx);
    if (r === false || ctx.res.writableEnded || ctx.sent) return;
  }
}
