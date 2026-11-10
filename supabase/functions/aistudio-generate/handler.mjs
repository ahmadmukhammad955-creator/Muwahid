const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, apikey, content-type, x-client-info', 'Access-Control-Allow-Methods': 'POST, OPTIONS', 'Cache-Control': 'no-store' };
const json = (status, code, message) => new Response(JSON.stringify({ code, message }), { status, headers: { ...cors, 'Content-Type': 'application/json' } });
export function makeHandler(env, request = fetch) {
  return async req => {
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (req.method !== 'POST') return json(405, 'METHOD_NOT_ALLOWED', 'Используйте POST.');
    const authorization = req.headers.get('authorization') || '';
    if (!/^Bearer \S+$/.test(authorization)) return json(401, 'LOGIN_REQUIRED', 'Войдите в аккаунт приложения.');
    try {
      const auth = await request(`${env('SUPABASE_URL')}/auth/v1/user`, {
        headers: { Authorization: authorization, apikey: env('SUPABASE_ANON_KEY') || '' }, signal: AbortSignal.timeout(10000),
      });
      if (!auth.ok) return json(401, 'LOGIN_REQUIRED', 'Войдите в аккаунт приложения повторно.');
      const user = await auth.json();
      if (!user.id || user.is_anonymous) return json(403, 'ACCESS_DENIED', 'Нужен зарегистрированный аккаунт.');
      const membership = await request(`${env('SUPABASE_URL')}/rest/v1/rpc/mw_family_access_status`, {
        method: 'POST',
        headers: { Authorization: authorization, apikey: env('SUPABASE_ANON_KEY') || '', 'Content-Type': 'application/json' },
        body: '{}', signal: AbortSignal.timeout(10000),
      });
      if (!membership.ok || await membership.json() !== 'active') {
        return json(403, 'FAMILY_ACCESS_REQUIRED', 'Требуется действующий семейный доступ.');
      }
      const allowed = (env('AISTUDIO_ALLOWED_USER_IDS') || '').split(',').map(s => s.trim()).filter(Boolean);
      if (!allowed.length) return json(503, 'ACCESS_NOT_CONFIGURED', 'Владелец должен настроить доступ к генератору.');
      if (!allowed.includes(user.id)) return json(403, 'ACCESS_DENIED', 'Для этого аккаунта генерация не разрешена.');
      const configured = env('AISTUDIO_FLUX_URL');
      if (!configured) return json(503, 'GENERATOR_NOT_CONFIGURED', 'FLUX-сервер ещё не подключён.');
      const upstream = new URL(configured);
      if (upstream.protocol !== 'https:' || upstream.username || upstream.password || upstream.hash) return json(503, 'INVALID_CONFIGURATION', 'Некорректная конфигурация генератора.');
      // The destination comes only from server secrets, never from the request.
      const raw = await req.text();
      if (raw.length > 16000) return json(413, 'BODY_TOO_LARGE', 'Запрос слишком большой.');
      let body;
      try { body = JSON.parse(raw); } catch { return json(400, 'INVALID_REQUEST', 'Некорректный JSON.'); }
      if (!body || typeof body.prompt !== 'string' || !body.prompt.trim() || body.prompt.trim().length > 2000) return json(400, 'INVALID_PROMPT', 'Введите описание от 1 до 2000 символов.');
      const size = body.width === 1024 ? 1024 : 768;
      const headers = { 'Content-Type': 'application/json', Accept: 'image/png, image/jpeg, image/webp' };
      const token = env('AISTUDIO_FLUX_TOKEN');
      if (token) headers.Authorization = `Bearer ${token}`;
      const response = await request(upstream.toString(), {
        method: 'POST', redirect: 'error', headers, signal: AbortSignal.timeout(120000),
        body: JSON.stringify({ prompt: body.prompt.trim(), width: size, height: size, steps: 4,
          seed: Number.isInteger(body.seed) && body.seed >= 0 && body.seed <= 2147483647 ? body.seed : 42 }),
      });
      if (!response.ok) {
        await response.body?.cancel();
        return json(response.status === 429 ? 429 : response.status === 400 ? 400 : 502, 'GENERATOR_ERROR', 'Генератор отклонил запрос или временно недоступен.');
      }
      const type = response.headers.get('content-type') || '';
      if (!/^image\/(png|jpeg|webp)(;|$)/i.test(type)) {
        await response.body?.cancel(); return json(502, 'INVALID_IMAGE', 'Генератор не вернул изображение.');
      }
      const reader = response.body?.getReader();
      if (!reader) return json(502, 'INVALID_IMAGE', 'Изображение пустое.');
      const chunks = []; let sizeBytes = 0;
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          sizeBytes += value.byteLength;
          if (sizeBytes > 20 * 1024 * 1024) { await reader.cancel(); return json(502, 'IMAGE_TOO_LARGE', 'Изображение превышает 20 МБ.'); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      if (!sizeBytes) return json(502, 'INVALID_IMAGE', 'Изображение пустое.');
      return new Response(new Blob(chunks, { type }), { headers: { ...cors, 'Content-Type': type } });
    } catch (e) {
      return json(e?.name === 'TimeoutError' ? 504 : 502, 'SERVICE_UNAVAILABLE', 'Сервер генерации недоступен. Попробуйте позже.');
    }
  };
}
