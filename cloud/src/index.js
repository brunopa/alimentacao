// Backend do Diario Alimentacao (Cloudflare Worker + R2 + D1)
//
// Rotas:
//   POST /upload                       -> recebe foto do app (auth: Bearer upload_token)
//   GET  /v/:viewToken                 -> pagina da nutricionista (somente leitura)
//   GET  /v/:viewToken/img/:id         -> entrega a imagem do R2
//   GET  /owner/:uploadToken           -> pagina do dono (editar tipo de refeicao / observacao)
//   POST /owner/:uploadToken/meal/:id  -> salva tipo/observacao (JSON {meal_type, note})
//   GET  /                             -> health check

const TZ = "America/Sao_Paulo";

const MEAL_TYPES = [
  "Cafe da manha",
  "Lanche da manha",
  "Almoco",
  "Lanche da tarde",
  "Jantar",
  "Ceia",
  "Outro",
];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const parts = path.split("/").filter(Boolean);

    try {
      if (request.method === "POST" && path === "/upload") {
        return await handleUpload(request, env);
      }
      if (request.method === "GET" && parts[0] === "v" && parts.length === 2) {
        return await handleViewer(env, parts[1], false);
      }
      if (request.method === "GET" && parts[0] === "v" && parts[2] === "img" && parts.length === 4) {
        return await handleImage(env, parts[1], parts[3], "view_token");
      }
      if (request.method === "GET" && parts[0] === "owner" && parts.length === 2) {
        return await handleOwner(env, parts[1]);
      }
      if (request.method === "GET" && parts[0] === "owner" && parts[2] === "img" && parts.length === 4) {
        return await handleImage(env, parts[1], parts[3], "upload_token");
      }
      if (request.method === "POST" && parts[0] === "owner" && parts[2] === "meal" && parts.length === 4) {
        return await handleEditMeal(request, env, parts[1], parts[3]);
      }
      if (path === "/") {
        return new Response("Diario Alimentacao - backend ativo.", {
          headers: { "content-type": "text/plain; charset=utf-8" },
        });
      }
      return new Response("Nao encontrado", { status: 404 });
    } catch (err) {
      return json({ ok: false, error: String((err && err.message) || err) }, 500);
    }
  },
};

// ---------- Upload ----------

async function handleUpload(request, env) {
  const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  if (!token) return json({ ok: false, error: "Token ausente" }, 401);

  const user = await env.DB.prepare("SELECT id FROM users WHERE upload_token = ?")
    .bind(token).first();
  if (!user) return json({ ok: false, error: "Token invalido" }, 403);

  const form = await request.formData();
  const photo = form.get("photo");
  if (!photo || typeof photo === "string") {
    return json({ ok: false, error: "Campo 'photo' ausente" }, 400);
  }

  const takenAt = Number(form.get("taken_at")) || Date.now();
  const filename = (form.get("filename") || "foto.jpg").toString();
  const device = (form.get("device") || "").toString();
  const mealType = mealTypeForMs(takenAt); // classificacao automatica por horario

  const r2Key = `${user.id}/${takenAt}_${filename}`;
  await env.FOTOS.put(r2Key, await photo.arrayBuffer(), {
    httpMetadata: { contentType: "image/jpeg" },
  });

  const res = await env.DB.prepare(
    `INSERT INTO meals (user_id, taken_at, filename, r2_key, device, meal_type, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).bind(user.id, takenAt, filename, r2Key, device, mealType, Date.now()).run();

  return json({ ok: true, id: res.meta.last_row_id, meal_type: mealType });
}

// ---------- Imagem ----------

async function handleImage(env, token, mealId, tokenColumn) {
  const row = await env.DB.prepare(
    `SELECT m.r2_key FROM meals m JOIN users u ON u.id = m.user_id
     WHERE u.${tokenColumn} = ? AND m.id = ?`
  ).bind(token, mealId).first();
  if (!row) return new Response("Nao encontrado", { status: 404 });

  const obj = await env.FOTOS.get(row.r2_key);
  if (!obj) return new Response("Imagem ausente", { status: 404 });

  return new Response(obj.body, {
    headers: { "content-type": "image/jpeg", "cache-control": "private, max-age=86400" },
  });
}

// ---------- Editar refeicao (dono) ----------

async function handleEditMeal(request, env, uploadToken, mealId) {
  const user = await env.DB.prepare("SELECT id FROM users WHERE upload_token = ?")
    .bind(uploadToken).first();
  if (!user) return json({ ok: false, error: "Token invalido" }, 403);

  const body = await request.json().catch(() => ({}));
  let mealType = (body.meal_type || "").toString();
  const note = (body.note || "").toString().slice(0, 500);
  if (mealType && !MEAL_TYPES.includes(mealType)) mealType = "Outro";

  const res = await env.DB.prepare(
    `UPDATE meals SET meal_type = ?, note = ? WHERE id = ? AND user_id = ?`
  ).bind(mealType || null, note || null, mealId, user.id).run();

  if (!res.meta.changes) return json({ ok: false, error: "Refeicao nao encontrada" }, 404);
  return json({ ok: true });
}

// ---------- Visualizador (nutricionista, somente leitura) ----------

async function handleViewer(env, viewToken) {
  const user = await env.DB.prepare("SELECT id, name FROM users WHERE view_token = ?")
    .bind(viewToken).first();
  if (!user) return new Response("Link invalido", { status: 404 });

  const { results } = await env.DB.prepare(
    `SELECT id, taken_at, device, meal_type, note FROM meals
     WHERE user_id = ? ORDER BY taken_at DESC`
  ).bind(user.id).all();

  return new Response(renderPage(user, viewToken, results || [], false), {
    headers: { "content-type": "text/html; charset=utf-8" },
  });
}

// ---------- Pagina do dono (editavel) ----------

async function handleOwner(env, uploadToken) {
  const user = await env.DB.prepare("SELECT id, name FROM users WHERE upload_token = ?")
    .bind(uploadToken).first();
  if (!user) return new Response("Link invalido", { status: 404 });

  const { results } = await env.DB.prepare(
    `SELECT id, taken_at, device, meal_type, note FROM meals
     WHERE user_id = ? ORDER BY taken_at DESC`
  ).bind(user.id).all();

  return new Response(renderPage(user, uploadToken, results || [], true), {
    headers: { "content-type": "text/html; charset=utf-8" },
  });
}

// ---------- Render ----------

function renderPage(user, token, meals, editable) {
  const imgBase = editable ? `/owner/${encodeURIComponent(token)}/img` : `/v/${encodeURIComponent(token)}/img`;

  // Agrupa: semana -> dia -> refeicoes
  const weeks = new Map();
  for (const meal of meals) {
    const p = localParts(meal.taken_at);
    const y = Number(p.year), m = Number(p.month), d = Number(p.day);
    const wk = weekInfo(y, m, d);
    if (!weeks.has(wk.key)) weeks.set(wk.key, { label: wk.label, anchor: wk.key, days: new Map() });
    const week = weeks.get(wk.key);
    const dayKey = `${p.year}-${p.month}-${p.day}`;
    const dayLabel = `${cap(p.weekday)}, ${p.day}/${p.month}/${p.year}`;
    if (!week.days.has(dayKey)) week.days.set(dayKey, { label: dayLabel, items: [] });
    week.days.get(dayKey).items.push({
      id: meal.id,
      time: `${p.hour}:${p.minute}`,
      mealType: meal.meal_type || "",
      note: meal.note || "",
    });
  }

  // Navegacao por semana
  let nav = "";
  for (const [, w] of weeks) nav += `<a href="#w${esc(w.anchor)}">${esc(w.label)}</a>`;

  let body = "";
  if (meals.length === 0) {
    body = `<p class="empty">Ainda nao ha fotos enviadas.</p>`;
  } else {
    for (const [, week] of weeks) {
      body += `<section class="week" id="w${esc(week.anchor)}"><h2>${esc(week.label)}</h2>`;
      for (const [, day] of week.days) {
        body += `<div class="day"><h3>${esc(day.label)} <span class="count">${day.items.length} foto(s)</span></h3><div class="grid">`;
        for (const it of day.items) {
          const src = `${imgBase}/${it.id}`;
          body += `<div class="card" data-id="${it.id}">
            <a href="${src}" target="_blank"><img loading="lazy" src="${src}" alt="refeicao"></a>
            <span class="time">${esc(it.time)}</span>
            ${renderMeta(it, editable)}
          </div>`;
        }
        body += `</div></div>`;
      }
      body += `</section>`;
    }
  }

  return `<!doctype html><html lang="pt-BR"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Diario - ${esc(user.name)}</title>
<style>
  :root { --verde:#2E7D32; --laranja:#FF8F00; }
  * { box-sizing:border-box; }
  body { margin:0; font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background:#f5f5f5; color:#222; }
  header { background:var(--verde); color:#fff; padding:14px 20px; position:sticky; top:0; z-index:20; }
  header h1 { margin:0; font-size:19px; }
  header p { margin:3px 0 0; opacity:.85; font-size:13px; }
  nav { display:flex; gap:8px; overflow-x:auto; padding:10px 16px; background:#fff; border-bottom:1px solid #e0e0e0; position:sticky; top:54px; z-index:15; }
  nav a { white-space:nowrap; font-size:13px; color:var(--verde); text-decoration:none; border:1px solid var(--verde); border-radius:14px; padding:4px 10px; }
  main { padding:16px; max-width:1000px; margin:0 auto; }
  .week > h2 { font-size:16px; color:var(--verde); border-bottom:2px solid var(--verde); padding-bottom:6px; margin-top:28px; scroll-margin-top:110px; }
  .day h3 { font-size:15px; margin:18px 0 10px; color:#444; }
  .day .count { color:#999; font-weight:normal; font-size:13px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:12px; }
  .card { position:relative; background:#fff; border-radius:10px; overflow:hidden; box-shadow:0 1px 3px rgba(0,0,0,.15); }
  .card img { width:100%; aspect-ratio:1/1; object-fit:cover; display:block; }
  .card .time { position:absolute; top:6px; right:6px; background:rgba(0,0,0,.55); color:#fff; font-size:12px; padding:2px 7px; border-radius:10px; }
  .meta { padding:8px; }
  .badge { display:inline-block; background:var(--laranja); color:#fff; font-size:11px; padding:2px 8px; border-radius:10px; margin-bottom:6px; }
  .note { font-size:13px; color:#555; white-space:pre-wrap; }
  .edit select, .edit input { width:100%; font-size:13px; padding:5px; margin-top:6px; border:1px solid #ccc; border-radius:6px; }
  .edit button { margin-top:6px; width:100%; font-size:13px; padding:6px; background:var(--verde); color:#fff; border:none; border-radius:6px; cursor:pointer; }
  .edit .saved { color:var(--verde); font-size:12px; }
  .empty { color:#888; text-align:center; margin-top:40px; }
  .hint { background:#fff8e1; border:1px solid #ffe082; padding:10px 14px; border-radius:8px; font-size:13px; margin-bottom:16px; }
</style></head><body>
<header><h1>Diario Alimentacao</h1><p>${esc(user.name)} &middot; ${meals.length} foto(s)${editable ? " &middot; modo edicao" : ""}</p></header>
<nav>${nav}</nav>
<main>
${editable ? `<div class="hint">Esta e a sua pagina (modo edicao). Marque o tipo de refeicao e adicione observacoes. A nutricionista ve tudo em um link separado, somente leitura.</div>` : ""}
${body}
</main>
${editable ? ownerScript(token) : ""}
</body></html>`;
}

function renderMeta(it, editable) {
  if (!editable) {
    let h = `<div class="meta">`;
    if (it.mealType) h += `<span class="badge">${esc(it.mealType)}</span>`;
    if (it.note) h += `<div class="note">${esc(it.note)}</div>`;
    h += `</div>`;
    return h;
  }
  const options = MEAL_TYPES.map(
    (t) => `<option value="${esc(t)}"${t === it.mealType ? " selected" : ""}>${esc(t)}</option>`
  ).join("");
  return `<div class="meta edit">
    <select class="mt"><option value="">(sem tipo)</option>${options}</select>
    <input class="nt" type="text" maxlength="500" placeholder="Observacao..." value="${esc(it.note)}">
    <button class="sv">Salvar</button>
    <span class="saved"></span>
  </div>`;
}

function ownerScript(token) {
  return `<script>
  const TK = ${JSON.stringify(token)};
  document.querySelectorAll(".card").forEach(card => {
    const btn = card.querySelector(".sv");
    if (!btn) return;
    btn.addEventListener("click", async () => {
      const id = card.getAttribute("data-id");
      const meal_type = card.querySelector(".mt").value;
      const note = card.querySelector(".nt").value;
      const saved = card.querySelector(".saved");
      btn.disabled = true; saved.textContent = "salvando...";
      try {
        const r = await fetch("/owner/" + encodeURIComponent(TK) + "/meal/" + id, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ meal_type, note })
        });
        const j = await r.json();
        saved.textContent = j.ok ? "salvo!" : ("erro: " + (j.error||""));
      } catch (e) { saved.textContent = "falha de rede"; }
      btn.disabled = false;
      setTimeout(() => saved.textContent = "", 2500);
    });
  });
  </script>`;
}

// ---------- Tempo / classificacao ----------

function localParts(ms) {
  const fmt = new Intl.DateTimeFormat("pt-BR", {
    timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit",
    weekday: "long", hour: "2-digit", minute: "2-digit", hour12: false,
  });
  const p = {};
  for (const part of fmt.formatToParts(new Date(ms))) p[part.type] = part.value;
  if (p.hour === "24") p.hour = "00";
  return p;
}

function mealTypeForMs(ms) {
  const h = Number(localParts(ms).hour);
  if (h >= 5 && h < 10) return "Cafe da manha";
  if (h >= 10 && h < 12) return "Lanche da manha";
  if (h >= 12 && h < 15) return "Almoco";
  if (h >= 15 && h < 18) return "Lanche da tarde";
  if (h >= 18 && h < 22) return "Jantar";
  return "Ceia";
}

function weekInfo(y, m, d) {
  const date = new Date(Date.UTC(y, m - 1, d));
  const offsetToMonday = (date.getUTCDay() + 6) % 7;
  const monday = new Date(date.getTime() - offsetToMonday * 86400000);
  const sunday = new Date(monday.getTime() + 6 * 86400000);
  const dd = (n) => String(n).padStart(2, "0");
  const key = `${monday.getUTCFullYear()}${dd(monday.getUTCMonth() + 1)}${dd(monday.getUTCDate())}`;
  const label = `Semana de ${dd(monday.getUTCDate())}/${dd(monday.getUTCMonth() + 1)} a ${dd(sunday.getUTCDate())}/${dd(sunday.getUTCMonth() + 1)}`;
  return { key, label };
}

// ---------- Utils ----------

function cap(s) { return s ? s.charAt(0).toUpperCase() + s.slice(1) : s; }
function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status, headers: { "content-type": "application/json; charset=utf-8" },
  });
}
