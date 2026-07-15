# RockService Mobile

Aplicativo Android aberto para diagnóstico, análise de firmware e manutenção **autorizada** de equipamentos Rockchip, respeitando os limites do Android e do hardware.

## Estado

`0.1.0-alpha01` é um bootstrap somente leitura:

- app Compose mínimo;
- detecção de capacidades;
- identificação inicial de firmware por magic bytes;
- SHA-256 em streaming;
- backend USB simulado;
- política de confirmação para operações destrutivas;
- módulos NDK vazios e encapsulados;
- nenhuma gravação real, nenhum loader, nenhum bypass e nenhum root.

## Compilação

Requisitos: Android Studio compatível com AGP 8.13.2, JDK 17, Android SDK 36, CMake 3.22.1 e NDK configurado.

```bash
./gradlew :app:assembleDebug test
```

O wrapper oficial do Gradle 8.13 está versionado em `gradle/wrapper/gradle-wrapper.jar` e a distribuição é validada por URL HTTPS no arquivo `gradle-wrapper.properties`.

Consulte `docs/FEASIBILITY.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md` e `SECURITY.md`.
