# AGENTS.md

## Git workflow (mandatory)

After **every** file edit made during a session, make a valid commit and push to GitHub.

- Repo: `git@github.com:tanmoymondal1312/Open-Cut.git`, branch `main`
- Commit message: short, imperative, describes the actual change
- Before committing always check `git status` and `git diff` — stage only intended files
- Never commit secrets, `local.properties`, or `build/` output
- Push: `git push` (set upstream already configured)

## Build

- Requires JDK: `C:\Program Files\Android\Android Studio\jbr` (set `JAVA_HOME`)
- Build: `.\gradlew.bat assembleDebug`
- Lint check: `.\gradlew.bat lintDebug`
- adb is not on PATH — use `C:\Users\tanmo\AppData\Local\Android\Sdk\platform-tools\adb.exe`
  (emulator `192.168.56.101:5555`, always `-s`, wake with `input keyevent 224`)

## Project conventions

- Single-screen UI, XML layouts only (no Jetpack Compose)
- All colors live in `res/values/colors.xml` — never hardcode hex in layouts
- All user-facing text lives in `res/values/strings.xml`
- Keep the CapCut-style dark panel theme
