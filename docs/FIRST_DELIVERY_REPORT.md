# Relatório da primeira entrega

## 1. Resumo executivo
Arquivos: `README.md`. Decisão: bootstrap somente leitura. Limitação: não substitui PC universalmente. Testes: revisão estrutural. Próxima etapa: build em Android SDK.

## 2. Viabilidade
Arquivo: `docs/FEASIBILITY.md`. Decisão: matriz por modo/capacidade. Limitação: USB e kernel variam. Próxima: PoC host em aparelhos reais.

## 3. Riscos
Arquivos: `SECURITY.md`, `docs/THREAT_MODEL.md`. Decisão: zero trust para firmware. Limitação: fuzzing ainda não integrado. Próxima: corpus sintético.

## 4. Arquitetura
Arquivo: `docs/ARCHITECTURE.md`. Decisão: ports/adapters e políticas. Limitação: Hilt ainda não ativado. Próxima: composition root.

## 5. Estrutura
Módulos compiláveis iniciais e módulos futuros em `modules-planned/`. Limitação: não ativar módulos vazios no Gradle.

## 6. Roadmap
Arquivo: `docs/ROADMAP.md`. Próxima: Fase 0 com USB somente leitura.

## 7. Modelo de ameaças
Arquivo: `docs/THREAT_MODEL.md`. Próxima: abuso de parsers e supply chain.

## 8. Dependências
Arquivo: `docs/DEPENDENCIES.md`. Decisão: licenças permissivas; libusb apenas avaliada. Limitação: auditoria final por versão ainda necessária.

## 9. GitHub
Arquivos `.github/*`, `CODEOWNERS`, `docs/GITHUB_SETUP.md`. Limitação: regras precisam ser aplicadas na interface/API do repositório.

## 10. CodeRabbit
Arquivo `.coderabbit.yaml`. Decisão: revisão assertiva e instruções por caminho. Limitação: requer app instalado no GitHub.

## 11. Actions
Workflows de CI, CodeQL, supply chain e release. Limitação: wrapper JAR e secrets não incluídos.

## 12. Android mínimo
`app/` Compose Material 3. Limitação: build não executado neste ambiente sem SDK/wrapper JAR.

## 13. Capacidades
`feature-device-detection/`. Testa USB Host, ABI e flags. Limitação: root fica UNKNOWN.

## 14. Firmware
`feature-firmware/`. Magic bytes, SHA-256 e limite de tamanho. Limitação: sem extração/repack.

## 15. USB simulado
`core-usb/`. Enumerar e ler deterministamente. Limitação: nenhum transporte real.

## 16. Testes
JUnit para política, parser e simulador. Resultado local: arquivos e invariantes verificados; Gradle não executado.

## 17. Primeiro PR
Arquivo `docs/FIRST_PR.md`. Próxima: gerar wrapper confiável, compilar e abrir draft PR.
