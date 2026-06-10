# Diário Alimentação

Monorepo de um sistema pessoal de diário alimentar por fotos:

- **Balance o celular → o app abre a câmera, tira a foto e salva com data/hora.**
- A foto é **enviada automaticamente** para a nuvem (Cloudflare).
- Uma **nutricionista** acompanha tudo por um link web, organizado **por semana e por dia**, com tipo de refeição e observações.

## Estrutura do repositório

```
alimentacao/
├── app/                  # Aplicativo Android (Kotlin nativo)
│   └── src/main/...       # ShakeService, CameraActivity, MainActivity, UploadWorker...
├── cloud/                # Backend Cloudflare (Worker + R2 + D1)
│   ├── src/index.js       # Rotas: /upload, /v/:token (nutri), /owner/:token (dono)
│   ├── schema.sql         # Tabelas users e meals
│   └── wrangler.toml      # Configuração do Worker / bindings
├── gradlew / gradlew.bat # Gradle wrapper
└── README.md
```

---

## 1) App Android (`app/`)

App nativo em **Kotlin**. Instala fora da Play Store (sideload).

### Como funciona
- `ShakeService`: serviço em primeiro plano que ouve o acelerômetro. Ao detectar um "balanço", abre a câmera.
- `CameraActivity`: usa CameraX, tira a foto sozinha e salva em `Pictures/DiarioAlimentacao/ALIM_aaaammdd_hhmmss.jpg`.
- `UploadWorker`: envia a foto para a nuvem com **reenvio automático** quando houver internet (WorkManager).
- Tela inicial: liga/desliga o shake, ajusta **sensibilidade** e **tempo de foco**, e configura **endereço do webservice + API token**.

### Pré-requisitos
- **JDK 17** (o projeto fixa `org.gradle.java.home` em `gradle.properties`; ajuste para o seu caminho se necessário).
- **Android SDK** com `platform android-33` e `build-tools 33.0.1`.
- Crie um `local.properties` na raiz apontando para o SDK:
  ```
  sdk.dir=C:\\Users\\SEU_USUARIO\\AppData\\Local\\Android\\Sdk
  ```

### Compilar o APK
```bash
./gradlew assembleDebug
# Windows:
.\gradlew.bat assembleDebug
```
O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

### Instalar no celular
- **Via USB (ADB):** ative "Opções do desenvolvedor" + "Depuração USB" e rode:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Sem cabo:** copie o `.apk` para o celular e toque para instalar (permita "fontes desconhecidas").

### Configurar o envio
No app, seção **"Envio para a web"**, preencha:
- **Endereço do webservice:** `https://SEU-WORKER.workers.dev/upload`
- **API token:** o `upload_token` do seu usuário (veja `cloud/`).

> **Xiaomi/Redmi/POCO (HyperOS/MIUI):** ative **Autostart** e deixe a bateria **sem restrições** para o shake funcionar com o app fechado.

---

## 2) Backend Cloudflare (`cloud/`)

Um único **Worker** que recebe as fotos, guarda no **R2** (arquivos) e no **D1** (banco), e serve as páginas web.

### Rotas
| Método | Rota | Para quê |
|--------|------|----------|
| `POST` | `/upload` | Recebe a foto do app (header `Authorization: Bearer <upload_token>`) |
| `GET`  | `/v/:viewToken` | Página da **nutricionista** (somente leitura) |
| `GET`  | `/owner/:uploadToken` | Página do **dono** (marcar refeição / observação) |
| `POST` | `/owner/:uploadToken/meal/:id` | Salva tipo/observação (JSON) |

O tipo de refeição é classificado automaticamente pelo horário (café, almoço, jantar...), e pode ser ajustado na página do dono.

### Contrato do upload (multipart/form-data)
- `photo` — arquivo JPEG
- `taken_at` — timestamp em milissegundos (epoch)
- `filename` — nome do arquivo
- `device` — modelo do aparelho

### Pré-requisitos
- **Node 18+** e conta **Cloudflare** (R2 precisa ser ativado uma vez no painel).

### Rodar / publicar
```bash
cd cloud
npm install
npx wrangler login              # autentica no navegador

# Cria os recursos (uma vez):
npx wrangler d1 create alimentacao-db          # copie o database_id para wrangler.toml
npx wrangler r2 bucket create alimentacao-fotos
npx wrangler d1 execute alimentacao-db --remote --file=schema.sql

# Publica:
npx wrangler deploy
```

### Cadastrar um usuário
Cada pessoa tem um `upload_token` (vai no app) e um `view_token` (link da nutricionista):
```bash
npx wrangler d1 execute alimentacao-db --remote --command \
 "INSERT INTO users (name, upload_token, view_token, created_at) \
  VALUES ('Nome', 'GERE_UM_TOKEN', 'GERE_OUTRO_TOKEN', strftime('%s','now')*1000);"
```
Depois acesse:
- Dono (editar): `https://SEU-WORKER.workers.dev/owner/<upload_token>`
- Nutricionista (ler): `https://SEU-WORKER.workers.dev/v/<view_token>`

### Desenvolvimento local
```bash
cd cloud
npx wrangler dev
```

---

## Tecnologias
- **App:** Kotlin, CameraX, WorkManager, AndroidX. `minSdk 26`, `compileSdk 33`.
- **Backend:** Cloudflare Workers, R2 (storage), D1 (SQLite). Sem dependências de runtime.

## Privacidade
O link da nutricionista usa um token secreto — sem ele, ninguém vê as fotos. Se vazar, gere um novo `view_token` no banco.
