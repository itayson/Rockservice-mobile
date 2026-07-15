# Relatório da primeira entrega

## 1. Resumo executivo

Arquivos: `README.md`. Decisão: bootstrap somente leitura. Limitação: não substitui PC universalmente. Validação atual: testes, lint e `assembleDebug` executados com sucesso no GitHub Actions. Próxima etapa: consolidar arquitetura e iniciar transporte Android USB Host somente leitura.

## 2. Viabilidade

Arquivo: `docs/FEASIBILITY.md`. Decisão: matriz por modo/capacidade. Limitação: USB e kernel variam. Próxima: PoC host em aparelhos reais autorizados.

## 3. Riscos

Arquivos: `SECURITY.md`, `docs/THREAT_MODEL.md`. Decisão: zero trust para firmware. Limitação: fuzzing ainda não integrado. Próxima: corpus sintético e fuzzing dos parsers.

## 4. Arquitetura

Arquivo: `docs/ARCHITECTURE.md`. Decisão: ports/adapters e políticas como arquitetura-alvo. Limitação: o entry point do bootstrap ainda instancia `CapabilityDetector` diretamente. Próxima: composition root com ViewModel/use cases antes de backends reais.

## 5. Estrutura

Módulos compiláveis iniciais e módulos futuros em `modules-planned/`. Limitação: não ativar módulos vazios no Gradle.

## 6. Roadmap

Arquivo: `docs/ROADMAP.md`. Próxima: completar a Fase 0 e iniciar USB Host somente leitura com dispositivos explicitamente suportados.

## 7. Modelo de ameaças

Arquivo: `docs/THREAT_MODEL.md`. Próxima: ampliar controles para abuso de parsers, lifecycle USB e supply chain.

## 8. Dependências

Arquivo: `docs/DEPENDENCIES.md`. Decisão: licenças permissivas; libusb apenas avaliada. Limitação: auditoria final por versão ainda necessária.

## 9. GitHub

Arquivos `.github/*`, `CODEOWNERS`, `docs/GITHUB_SETUP.md`. Limitação: regras de proteção precisam ser aplicadas nas configurações do repositório.

## 10. CodeRabbit

Arquivo `.coderabbit.yaml`. Decisão: revisão assertiva e instruções por caminho. Estado: revisão automatizada ativa no PR inicial, com correções incorporadas ao bootstrap.

## 11. Actions

Workflows de CI, CodeQL e supply chain ativos. O Gradle Wrapper confiável está incluído e verificado por checksum. A publicação automática de release permanece deliberadamente desativada até existirem assinatura verificável, SBOM e gates completos.

## 12. Android mínimo

`app/` usa Compose Material 3. Resultado: APK debug gerado com sucesso no CI. Limitação: ainda não há validação em hardware físico nem transporte USB real.

## 13. Capacidades

`feature-device-detection/`. Testa USB Host, ABI e flags. Limitação: root permanece desativado no bootstrap.

## 14. Firmware

`feature-firmware/`. Magic bytes, SHA-256 e limite de tamanho. Limitação: sem extração/repack e sem execução de conteúdo importado.

## 15. USB simulado

`core-usb/`. Enumeração e leitura determinística com validação de alvo, limites, timeout e lifecycle. Limitação: nenhum transporte real.

## 16. Testes

JUnit cobre política, parser e simulador. Resultado no GitHub Actions: testes, lint e build Android concluídos com sucesso; CodeQL também foi executado para Java/Kotlin e C++.

## 17. Primeiro PR

PR #1: fundação segura do projeto. Estado: draft enquanto os últimos comentários de revisão e controles de bootstrap são concluídos. Próxima etapa técnica: backend Android USB Host estritamente somente leitura e validado por allowlist.
