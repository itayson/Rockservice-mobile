# Sessão Rockchip somente leitura — núcleo abstrato

## Objetivo

Esta camada organiza consultas de metadados Rockchip em uma sequência serial de comando, resposta e status, sem implementar transporte Android real.

## Fronteira de transporte

`RockchipReadOnlyTransport` é `internal` ao módulo. A camada de aplicação não recebe uma API para transmitir bytes arbitrários.

A sessão aceita somente `RockchipReadOnlyOperation`, portanto todo comando passa pela allowlist do codec antes de chegar ao transporte abstrato.

## Garantias

- operações são serializadas por `Mutex`;
- cada comando recebe uma tag incremental;
- timeouts devem ser positivos;
- cancelamento de coroutine é preservado;
- assinatura e tag do CSW são validadas;
- status diferente de `PASSED` falha a consulta;
- encerramento é idempotente;
- consultas após `close()` são rejeitadas.

## Estado atual

Não existe implementação Android de `RockchipReadOnlyTransport`. Consequentemente, esta sessão não executa `bulkTransfer()`, não reivindica interfaces e não envia qualquer CBW a hardware físico.

Uma implementação de transporte só deve ser adicionada após validação específica de interface/endpoints, permissão Android, revalidação de alvo e testes com hardware autorizado.
