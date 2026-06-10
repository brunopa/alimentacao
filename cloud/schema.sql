-- Usuarios: cada pessoa tem um token de upload (app) e um token de visualizacao (link da nutricionista).
CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          TEXT NOT NULL,
  upload_token  TEXT NOT NULL UNIQUE,
  view_token    TEXT NOT NULL UNIQUE,
  created_at    INTEGER NOT NULL
);

-- Refeicoes: uma linha por foto enviada.
CREATE TABLE IF NOT EXISTS meals (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL,
  taken_at    INTEGER NOT NULL,   -- epoch em milissegundos
  filename    TEXT NOT NULL,
  r2_key      TEXT NOT NULL,
  device      TEXT,
  note        TEXT,
  meal_type   TEXT,
  created_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meals_user_taken ON meals(user_id, taken_at);
