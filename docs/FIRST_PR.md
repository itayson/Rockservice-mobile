# Primeiro pull request

```bash
git clone <URL>
cd RockService-Mobile
git switch -c feat/bootstrap-foundation
gradle wrapper --gradle-version 8.13
./gradlew test :app:assembleDebug lint
git add .
git commit -m "feat: bootstrap secure Android foundation"
git push -u origin feat/bootstrap-foundation
gh pr create --draft --title "feat: bootstrap secure Android foundation"
```

No PR, declare explicitamente: nenhuma gravação real, nenhum teste em hardware e backend USB simulado.
