# DEV_GUIDE — PaperFX (UDP)

## 1) Репозиторий и сборка
- Java: 17
- Gradle wrapper: `./gradlew`

Команды:
```bash
./gradlew clean
./gradlew :server:run
./gradlew :client:run
```

---

## 2) Настройка БД
В корне есть `.env` и `docker-compose.yaml`.

Стандартный сценарий:
```bash
docker compose up -d
```

Проверка подключения (по умолчанию):
- host: `127.0.0.1`
- port: `5433`
- db: `paperfx`
- user/pass: `paperfx`

---

## 3) Переменные окружения сервера
Сервер использует:

- `DB_URL` (по умолчанию `jdbc:postgresql://127.0.0.1:5433/paperfx`)
- `DB_USER`
- `DB_PASS`

PowerShell:
```powershell
$env:DB_URL="jdbc:postgresql://127.0.0.1:5433/paperfx"
$env:DB_USER="paperfx"
$env:DB_PASS="paperfx"
```

Gradle таск `:server:run` подхватывает значения из `.env`, либо из окружения.

---

## 4) Сеть и протокол (UDP)
- Сервер: `DatagramSocket`, порт по умолчанию `7777`
- Клиент: `DatagramSocket` с локальным «эпемерным» портом
- 1 UDP-датаграмма = 1 JSON-сообщение (UTF‑8)
- Спецификация сообщений — в `PROTOCOL.md`

---

## 5) Где что менять
- Добавление новых сообщений протокола: `common/Messages.java` + обработка в `server/ServerMain.java` и клиенте
- Игровая логика комнаты: `server/Room.java`
- Persistence/статистика/достижения: `server/Db.java`, `server/ServerMain.java`
- UI/рендер и ввод: `client/PaperFxApp.java`
