# Roadmap

## Fase 0 — Pesquisa e protótipos

Matriz de viabilidade, USB Host, detecção Rockchip, NDK, licenças e segurança.

## Fase 1 — Base do aplicativo

Arquitetura, UI, logs, banco local, importação, hashes e relatórios básicos.

## Fase 2 — Laboratório de firmware

Formatos, extração segura, sparse/raw, boot images e partições dinâmicas.

## Fase 3 — ADB e diagnóstico

ADB autorizado, logs, saúde, rede e armazenamento.

## Fase 4 — Rockchip somente leitura

USB, chip, armazenamento, partições e backup; sem escrita.

## Fase 5 — Gravação experimental

A escrita só poderá ser introduzida após todos os seguintes gates:

- feature flag desativada por padrão;
- allowlist de dispositivos e operações explicitamente testados;
- confirmação textual específica para o alvo e a operação;
- revalidação do dispositivo, chip, memória e destino imediatamente antes da escrita;
- testes automatizados para validações, falhas parciais e recuperação;
- evidência de hardware autorizado para cada combinação suportada;
- abortamento seguro em desconexão, timeout ou condição de energia insuficiente;
- backup recomendado ou obrigatório conforme o risco;
- plano de recuperação documentado e testado;
- releitura e verificação de integridade após a gravação.

## Fase 6 — Recuperação avançada

Loader/Maskrom tecnicamente viáveis, loaders importados pelo usuário e matriz de compatibilidade.

## Fase 7 — Plugins e programadores externos

API de plugins, USB/serial e documentação para fabricantes.
