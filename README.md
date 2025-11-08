# PaperFX (Rooms + Auth + Postgres)

## Requirements covered
- Java networking: only Socket / ServerSocket (TCP)
- Postgres persistence: users + results + leaderboard (best score)
- Auth: register/login with PBKDF2 password hashes
- Rooms: create/join by roomId, max 4 players, join in progress

## Run database
```bash
docker compose up -d
```

## Run server
Set env vars (PowerShell):
```powershell
$env:DB_URL="jdbc:postgresql://127.0.0.1:5433/paperfx"
$env:DB_USER="paperfx"
$env:DB_PASS="paperfx"
```

Then:
```bash
gradle :server:run
```
(or `./gradlew :server:run` if you have wrapper)

## Run client
```bash
gradle :client:run
```

## Rooms
- After auth, client auto-joins room `MAIN`.
- Create room -> server returns new room id and joins you.
- Join room -> enter room id (e.g. MAIN) and press Join.

## Territory stealing & scoring
- When you close a loop, every cell inside becomes yours (even if it was owned by others).
- Score is current territory size (cells). When you steal, your score goes up and the victim's score goes down automatically.
