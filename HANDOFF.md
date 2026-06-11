# HANDOFF — Diário Alimentação (contexto para retomar o trabalho)

> Documento de continuidade. Se você (Claude) está lendo isto após um reinício, este
> arquivo resume **tudo** que importa sobre o projeto: arquitetura, decisões, estado atual,
> comandos e o que ainda falta. Idioma do usuário (Bruno): **português do Brasil**.

Última atualização: 2026-06-10.

---

## 1. Visão geral / objetivo

Sistema pessoal de **diário alimentar por fotos**:

1. O usuário **balança o celular** → o app Android abre a câmera, **tira a foto sozinho** e salva com data/hora.
2. A foto é **enviada automaticamente** para a nuvem (Cloudflare), com **reenvio resiliente** (WorkManager) quando houver internet.
3. Uma **nutricionista** acompanha tudo por um **link web somente leitura**, organizado **por semana e por dia**, com **tipo de refeição** (classificado pelo horário) e **observações**.
4. O **dono** tem uma página própria (editável) para ajustar tipo de refeição e adicionar notas.

App é **sideload** (APK próprio, fora da Play Store).

---

## 2. Estrutura do monorepo

```
alimentacao/
├── app/                  # App Android (Kotlin nativo)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/bruno/alimentacao/
│       │   ├── MainActivity.kt     # tela inicial: liga/desliga shake, ajustes, config de envio, teste
│       │   ├── ShakeService.kt     # serviço em 1º plano: ouve acelerômetro, abre câmera
│       │   ├── CameraActivity.kt   # CameraX: auto-captura, salva e enfileira upload
│       │   ├── UploadWorker.kt     # WorkManager: envio resiliente com retry
│       │   ├── Uploader.kt         # multipart HttpURLConnection (síncrono + teste)
│       │   └── Prefs.kt            # SharedPreferences (sensibilidade, delay, url, token)
│       └── res/layout/{activity_main.xml, activity_camera.xml}
├── cloud/                # Backend Cloudflare
│   ├── src/index.js      # Worker (rotas, upload, render das páginas, classificação)
│   ├── schema.sql        # tabelas users e meals
│   ├── wrangler.toml     # bindings R2/D1, account_id
│   └── package.json      # wrangler devDependency
├── gradlew / gradlew.bat / gradle/wrapper/
├── README.md             # instruções públicas de build/deploy
├── HANDOFF.md            # este arquivo
└── .gitignore
```

**GitHub:** https://github.com/brunopa/alimentacao (público, branch `main`).

**Worker (produção):** https://alimentacao.bruno-5fe.workers.dev (conta `bruno-5fe` / Bruno@buscaprev.com.br).

---

## 3. App Android — detalhes técnicos

- **Pacote / applicationId:** `com.bruno.alimentacao`
- **Build:** AGP 8.1.x, Kotlin, **JDK 17** (fixado em `gradle.properties` → `org.gradle.java.home=C:/Program Files/Java/jdk-17`), Gradle wrapper 8.2.
- **SDKs:** `compileSdk = 33`, `buildToolsVersion = "33.0.1"`, `minSdk = 26`, `targetSdk = 33`. ViewBinding ligado.
- **Dependências chave:** CameraX **1.2.3** (NÃO subir para 1.3.x sem compileSdk 34), WorkManager `2.8.1`, AppCompat 1.6.1, Material 1.9.0.
- **`android.suppressUnsupportedCompileSdk=33`** está setado para silenciar aviso.

### Fluxo
- `ShakeService` (foreground, `foregroundServiceType="camera"`): lê acelerômetro em `SENSOR_DELAY_GAME`. Dispara quando `gForce > threshold`. `COOLDOWN_MS = 3000`. Reage a mudança de sensibilidade ao vivo via `OnSharedPreferenceChangeListener`.
- `CameraActivity`: CameraX, `showWhenLocked`+`turnScreenOn`, captura após `Prefs.delayMs()`, salva via MediaStore em `Pictures/DiarioAlimentacao/ALIM_yyyyMMdd_HHmmss.jpg`. Se `Prefs.uploadConfigured()`, chama `UploadWorker.enqueue(...)`.
- `UploadWorker`: `Worker` com constraint `NetworkType.CONNECTED`. Em falha → `Result.retry()` (backoff automático).
- `Uploader`: monta `multipart/form-data` com campos `photo`, `taken_at` (ms), `filename`, `device` (Build.MODEL); header `Authorization: Bearer <token>` se houver. Sucesso = HTTP 2xx.

### Prefs (SharedPreferences "alimentacao_prefs")
- `sens_index`: 0=Baixa / 1=Normal / 2=Alta. `THRESHOLDS = [2.9, 2.3, 1.7]` (g). **Menor limiar = mais sensível.** Default 1.
- `delay_ms`: tempo de foco antes da foto. Default 1500. SeekBar arredonda em 100 ms, range 500–3000.
- `ws_url`: endereço do `/upload`. `api_token`: upload_token do usuário.

### Permissões (Manifest)
INTERNET, ACCESS_NETWORK_STATE, CAMERA, FOREGROUND_SERVICE, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW (overlay, pra abrir câmera de trás), WRITE_EXTERNAL_STORAGE (maxSdk 28).

### Compilar / instalar
```bash
.\gradlew.bat assembleDebug        # Windows (PowerShell)
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Observações de campo (importante)
- Celular de teste do Bruno: **Xiaomi/Redmi/POCO (HyperOS/MIUI)**, `Build.MODEL = 2412DPC0AG`.
- Em HyperOS/MIUI: ativar **Autostart** e bateria **sem restrições**, senão o shake morre com o app fechado.
- O app já foi compilado, instalado e testado no celular; pipeline ponta-a-ponta validado.

---

## 4. Backend Cloudflare — detalhes técnicos

- **Worker** em JS puro (`cloud/src/index.js`), sem dependências de runtime.
- **wrangler.toml:**
  - `name = "alimentacao"`, `main = "src/index.js"`, `compatibility_date = "2024-11-01"`
  - `account_id = "5fec1f1593e61dd7cbd77272ccec2a53"` (conta **Bruno@buscaprev.com.br** — escolhida porque há mais de uma conta no login do wrangler)
  - R2 binding `FOTOS` → bucket `alimentacao-fotos`
  - D1 binding `DB` → `alimentacao-db`, `database_id = "9d063131-2695-48be-8e23-8c1d0c995a99"`
- **R2 precisou ser ativado manualmente** no painel uma vez (já feito).
- Usa `npx wrangler` (CLI não instalado globalmente; roda via npx, baixa wrangler 4.x).

### Rotas
| Método | Rota | Função |
|--------|------|--------|
| POST | `/upload` | recebe foto (header `Authorization: Bearer <upload_token>`); classifica refeição por horário; grava R2 + D1 |
| GET | `/v/:viewToken` | página da **nutricionista** (somente leitura) |
| GET | `/v/:viewToken/img/:id` | entrega imagem do R2 (valida via `view_token`) |
| GET | `/owner/:uploadToken` | página do **dono** (editável) |
| GET | `/owner/:uploadToken/img/:id` | imagem (valida via `upload_token`) |
| POST | `/owner/:uploadToken/meal/:id` | salva `{meal_type, note}` (JSON) |
| POST | `/owner/:uploadToken/meal/:id/delete` | **exclui** a foto (apaga do R2 + D1) |
| GET | `/` | health check |

### Classificação automática por horário (timezone America/Sao_Paulo)
`Café da manhã` 05–10 · `Lanche da manhã` 10–12 · `Almoço` 12–15 · `Lanche da tarde` 15–18 · `Jantar` 18–22 · `Ceia` (resto). `MEAL_TYPES` inclui também `Outro`.

### Agrupamento na página
semana (segunda→domingo, calculada em UTC a partir das partes locais) → dia → cards de refeição. Nav por semana no topo. Modo `editable` adiciona `<select>` + input de nota + botão Salvar (fetch para `/owner/.../meal/:id`).

### Schema (D1)
```sql
users(id, name, upload_token UNIQUE, view_token UNIQUE, created_at)
meals(id, user_id, taken_at /*ms*/, filename, r2_key, device, note, meal_type, created_at)
INDEX idx_meals_user_taken ON meals(user_id, taken_at)
```
`r2_key` = `${user_id}/${takenAt}_${filename}`.

### Deploy / operação
```bash
cd cloud
npm install
npx wrangler login
# uma vez:
npx wrangler d1 create alimentacao-db        # copiar database_id pro wrangler.toml
npx wrangler r2 bucket create alimentacao-fotos
npx wrangler d1 execute alimentacao-db --remote --file=schema.sql
# publicar:
npx wrangler deploy
```

### Cadastrar usuário
```bash
npx wrangler d1 execute alimentacao-db --remote --command \
 "INSERT INTO users (name, upload_token, view_token, created_at) \
  VALUES ('Nome', 'TOKEN_UPLOAD', 'TOKEN_VIEW', strftime('%s','now')*1000);"
```

### Consultar fotos recebidas
```bash
npx wrangler d1 execute alimentacao-db --remote --command \
 "SELECT id, datetime(taken_at/1000,'unixepoch','-3 hours') AS hora, filename, device, meal_type FROM meals ORDER BY id DESC LIMIT 10;"
```

---

## 5. Segredos e privacidade

- **Tokens (upload_token / view_token) NÃO estão no repositório.** Vivem só no D1.
- `account_id` e `database_id` estão no `wrangler.toml` (não são segredos sensíveis).
- `.gitignore` cobre: `build/`, `app/build/`, `*.apk`, `local.properties`, `cloud/node_modules/`, `cloud/.wrangler/`, `cloud/prefs_seed.xml`, `.claude/settings.local.json`, keystores, `.env*`.
- `cloud/prefs_seed.xml` (semeava tokens no app via `run-as`) foi **removido do repo** e gitignorado.
- Segurança do link da nutri = obscuridade do `view_token`. Se vazar, gerar novo no D1.

---

## 6. Estado atual (o que está PRONTO)

- ✅ App completo: shake → foto → save → upload resiliente. Ajustes de sensibilidade e tempo.
- ✅ Campos de webservice URL + API token no app (preparado para multiusuário).
- ✅ Backend completo: upload, classificação por horário, páginas nutri (leitura) e dono (edição), navegação por semana, entrega de imagem do R2.
- ✅ Botão **Excluir foto** na página do dono (rota `/owner/.../meal/:id/delete`, apaga R2+D1). Motivo: sensor de chacoalho estava sensível e subiu fotos indesejadas. **Pendente (falado pelo Bruno):** rever camada de permissão para exclusão — hoje qualquer um com o `upload_token` exclui, sem confirmação extra além do `confirm()` do navegador.
- ✅ Monorepo publicado no GitHub (`brunopa/alimentacao`, commit inicial em `main`).
- ✅ README com instruções de build/deploy.
- ✅ Pipeline validado ponta-a-ponta: última foto real `id=4` (`ALIM_20260610_203603.jpg`, device 2412DPC0AG, classificada "Jantar") confirmada no D1 via fluxo WorkManager.

---

## 7. Ambiente / pegadinhas conhecidas (LER antes de agir)

- **SO:** Windows. **Use a ferramenta PowerShell** para comandos do sistema (a Bash daqui é um bash limitado e já causou erro de sintaxe). Git funciona em ambas.
- **PowerShell 5.1:** sem `&&`/`||` (use `;` + `if ($?)`), sem `Invoke-WebRequest -Form` (use `curl.exe` para multipart).
- **api.github.com está BLOQUEADO nesta rede** (timeout consistente na porta 443). Consequência: **`gh` CLI não funciona** (auth e qualquer comando que use a API falham). `github.com` comum funciona → **`git push`/`git clone` por HTTPS funcionam normalmente.** Para criar repos, use a web (github.com/new), não o `gh`.
- `gh` (GitHub CLI) **está instalado** em `C:\Program Files\GitHub CLI\gh.exe` mas é inútil enquanto a API estiver bloqueada.
- Config git: user **"Bruno Almeida"**, email `bruno.hung@gmail.com`. Usuário GitHub: **brunopa**. Credenciais do push já estão em cache (push não pediu navegador).
- **adb + HyperOS:** `run-as ... >` (redirecionamento) dá "Permission denied"; use `cp` em vez de `>`.
- **CameraX:** travado em 1.2.3 por causa do compileSdk 33. Subir CameraX exige compileSdk 34 (instalar a platform antes).

---

## 8. Possíveis próximos passos (ideias, não pedidos ainda)

- Melhorar visual da página da nutricionista / adicionar resumo por dia.
- Filtro/contagem por tipo de refeição; exportar relatório.
- Múltiplos usuários: UI/fluxo para cadastrar e distribuir tokens.
- Compressão/limite de tamanho da foto antes do upload.
- Tela no app mostrando histórico/últimos envios e status do WorkManager.
- Versão release assinada do APK (keystore) — hoje só debug.

> Antes de mexer, **confirmar com o Bruno** o que ele quer priorizar. Ele prefere
> recomendações diretas a muitas perguntas.

---

## 9. Histórico de decisões do usuário

- Escolheu **Cloudflare** (Worker+R2+D1) para o backend, mesmo tendo conta Supabase ("cou de cloudflare").
- Quis ajuste de sensibilidade como Baixa/Normal/Alta + tempo de foco.
- Pediu campos de URL/token "pensando que mais gente poderá usar no futuro".
- Pediu: implementar as 3 melhorias, publicar como **monorepo** no GitHub com instruções. (Tudo feito.)
