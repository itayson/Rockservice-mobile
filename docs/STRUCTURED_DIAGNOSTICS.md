# Log técnico estruturado e sanitizado

## Objetivo

O RockService Mobile mantém um buffer técnico em memória para registrar eventos operacionais reproduzíveis sem persistir automaticamente dados do equipamento.

O mecanismo é implementado por `DiagnosticEventRecorder`, em `core-common`, para poder ser reutilizado pelas features sem depender de APIs Android.

## Estrutura do evento

Cada evento contém:

- sequência monotônica local;
- timestamp Unix em milissegundos;
- severidade;
- componente;
- ação;
- mensagem;
- metadados chave/valor sanitizados.

## Limites

O recorder:

- mantém uma quantidade máxima configurável de eventos;
- descarta primeiro os eventos mais antigos;
- limita tamanho de componente, ação, mensagem, chaves e valores;
- limita a quantidade de metadados por evento;
- remove quebras de linha e tabulações dos campos textuais.

O aplicativo usa um buffer process-local de até 1000 eventos. Reiniciar o processo remove o conteúdo.

## Redaction

Antes de um evento entrar no buffer, chaves sensíveis conhecidas são substituídas por `[redacted]`.

A política inclui fragmentos associados a:

- authorization;
- cookie;
- credential;
- password;
- private key;
- secret;
- serial;
- token;
- transportId.

A redaction ocorre antes da retenção, portanto o valor original não fica disponível para a tela ou para a exportação.

Os chamadores ainda devem evitar colocar conteúdo bruto de firmware, setores, credenciais ou dados pessoais na mensagem livre.

## Exportação

A tela `DiagnosticsLogActivity` permite exportar explicitamente um snapshot em JSON Lines (`.jsonl`) usando o Storage Access Framework.

A aplicação:

- não solicita permissão ampla de armazenamento;
- não escolhe o destino sem interação do operador;
- não exporta automaticamente;
- não mantém um arquivo de log próprio em segundo plano.

Cada linha do arquivo exportado é um objeto JSON independente.

## Integração inicial

O primeiro produtor integrado é o fluxo de diagnóstico USB, que registra:

- solicitação de nova enumeração;
- conclusão ou falha sanitizada da enumeração;
- cancelamento por uma solicitação mais nova;
- solicitação e processamento de seleção de alvo.

`transportId` pode ser usado como metadata pelo chamador, mas é redigido antes da retenção.

## Próximas integrações

As features de firmware, ADB e futuros fluxos críticos devem usar o mesmo recorder para eventos operacionais, mantendo as mesmas regras de sanitização e sem introduzir persistência automática paralela.
