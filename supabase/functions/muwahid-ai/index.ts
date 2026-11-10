
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const headers = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
  "cache-control": "no-store",
  "content-type": "application/json; charset=utf-8",
};
const respond = (status: number, code: string, message: string, extra: Record<string, unknown> = {}) =>
  new Response(JSON.stringify({ code, message, ...extra }), { status, headers });
const taskInstructions: Record<string, string> = {
  chat: "Ты помощник Muwahid AI. Отвечай на языке пользователя, кратко и точно.",
  rewrite: "Перепиши текст ясно, естественно и грамотно. Не меняй факты и смысл. Верни только готовый текст.",
  translate: "Переведи текст на русский язык, если пользователь не указал другой язык явно. Сохрани смысл и структуру. Верни только перевод.",
  summary: "Сделай краткий достоверный пересказ текста, не добавляя фактов. Верни только пересказ.",
};
const env = (name: string) => Deno.env.get(name) || "";
// Owner chose to prepare AI without paid API use. Opening this release gate
// requires a reviewed deployment as well as MUWAHID_AI_ENABLED=true.
const AI_RELEASE_ENABLED = false;

async function callDatabase<T>(path: string, adminKey: string, method: string, body?: unknown): Promise<T> {
  const response = await fetch(env("SUPABASE_URL") + "/rest/v1/" + path, {
    method,
    headers: {
      apikey: adminKey,
      Authorization: "Bearer " + adminKey,
      "content-type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error("database request failed: " + response.status);
  return await response.json() as T;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers });
  if (req.method !== "POST") return respond(405, "METHOD_NOT_ALLOWED", "Используйте POST.");

  // Opt-in only: deploying this function does not switch on AI costs or purchases.
  if (!AI_RELEASE_ENABLED || env("MUWAHID_AI_ENABLED") !== "true") {
    return respond(503, "AI_DISABLED", "Muwahid AI пока не включён.");
  }
  const openaiKey = env("OPENAI_API_KEY");
  const adminKey = env("SUPABASE_SERVICE_ROLE_KEY");
  const anonKey = env("SUPABASE_ANON_KEY");
  if (!openaiKey || !adminKey || !anonKey || !env("SUPABASE_URL")) {
    return respond(503, "AI_NOT_CONFIGURED", "Администратор ещё не завершил настройку AI.");
  }

  const authorization = req.headers.get("authorization") || "";
  if (!/^Bearer\s+\S+$/.test(authorization)) {
    return respond(401, "LOGIN_REQUIRED", "Войдите в Muwahid.");
  }

  let userId = "";
  let usageDay = "";
  let reserved = false;
  let successful = false;
  try {
    const authResponse = await fetch(env("SUPABASE_URL") + "/auth/v1/user", {
      headers: { Authorization: authorization, apikey: anonKey },
      signal: AbortSignal.timeout(10000),
    });
    if (!authResponse.ok) return respond(401, "LOGIN_REQUIRED", "Сессия истекла. Войдите снова.");
    const user = await authResponse.json();
    if (!user?.id || user.is_anonymous) return respond(403, "ACCOUNT_REQUIRED", "Нужен зарегистрированный аккаунт.");
    const membership = await fetch(env("SUPABASE_URL") + "/rest/v1/rpc/mw_family_access_status", {
      method: "POST",
      headers: { Authorization: authorization, apikey: anonKey, "Content-Type": "application/json" },
      body: "{}",
      signal: AbortSignal.timeout(10000),
    });
    if (!membership.ok || await membership.json() !== "active") {
      return respond(403, "FAMILY_ACCESS_REQUIRED", "Требуется действующий семейный доступ.");
    }
    userId = user.id;

    const raw = await req.text();
    if (raw.length > 10000) return respond(413, "BODY_TOO_LARGE", "Слишком длинный запрос.");
    let body: unknown;
    try { body = JSON.parse(raw); } catch { return respond(400, "INVALID_JSON", "Некорректный запрос."); }
    if (!body || typeof body !== "object") return respond(400, "INVALID_INPUT", "Некорректный запрос.");
    const parsed = body as Record<string, unknown>;
    const task = typeof parsed.task === "string" ? parsed.task : "chat";
    const input = typeof parsed.text === "string" ? parsed.text.trim() : "";
    if (!Object.prototype.hasOwnProperty.call(taskInstructions, task) || !input || input.length > 3000) {
      return respond(400, "INVALID_INPUT", "Введите текст длиной от 1 до 3000 символов и допустимую задачу.");
    }

    const access = await callDatabase<Array<{ tier: string; premium_until: string | null }>>(
      "mw_ai_access?user_id=eq." + encodeURIComponent(userId) + "&select=tier,premium_until",
      adminKey, "GET");
    const paid = access[0]?.tier === "premium" &&
      Boolean(access[0]?.premium_until) &&
      Date.parse(access[0].premium_until as string) > Date.now();
    const tier = paid ? "premium" : "free";
    const dailyLimit = paid ? 100 : 5;
    usageDay = new Date().toISOString().slice(0, 10);
    const remaining = await callDatabase<number>("rpc/mw_ai_consume_daily_request", adminKey, "POST", {
      p_user_id: userId, p_usage_day: usageDay, p_daily_limit: dailyLimit,
    });
    if (remaining < 0) {
      return respond(429, "DAILY_LIMIT", "Лимит запросов на сегодня исчерпан.", { tier, daily_limit: dailyLimit, remaining: 0 });
    }
    reserved = true;

    const aiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { Authorization: "Bearer " + openaiKey, "content-type": "application/json" },
      body: JSON.stringify({
        model: env("MUWAHID_AI_MODEL") || "gpt-6-luna",
        instructions: taskInstructions[task],
        input,
        max_output_tokens: 650,
        store: false,
      }),
      signal: AbortSignal.timeout(35000),
    });
    if (!aiResponse.ok) {
      await aiResponse.body?.cancel();
      return respond(aiResponse.status === 429 ? 503 : 502, "AI_UNAVAILABLE", "Сервис ИИ сейчас недоступен.");
    }
    const output = await aiResponse.json();
    const result = (output.output || [])
      .filter((item: { type?: string }) => item.type === "message")
      .flatMap((item: { content?: Array<{ type?: string; text?: string }> }) => item.content || [])
      .filter((item: { type?: string }) => item.type === "output_text")
      .map((item: { text?: string }) => item.text || "")
      .join("\n").trim();
    if (!result) return respond(502, "EMPTY_AI_RESPONSE", "ИИ не вернул текст. Попробуйте позже.");
    successful = true;
    return new Response(JSON.stringify({
      result, tier, daily_limit: dailyLimit, remaining,
    }), { status: 200, headers });
  } catch (_) {
    return respond(502, "AI_SERVICE_ERROR", "Не удалось обработать запрос. Попробуйте позже.");
  } finally {
    if (reserved && !successful && userId && usageDay) {
      try {
        await callDatabase("rpc/mw_ai_refund_daily_request", adminKey, "POST", {
          p_user_id: userId, p_usage_day: usageDay,
        });
      } catch (_) { /* error logged by database; avoid exposing internals */ }
    }
  }
});
