# Parsers de metadados Rockchip

## Objetivo

Esta camada converte respostas já validadas pelo codec em modelos simples, sem executar transporte USB e sem atribuir significado não confirmado aos campos desconhecidos.

## Dados interpretados

### Chip info

Exige exatamente 16 bytes e preserva o conteúdo como hexadecimal estável.

### Flash ID

Exige exatamente 5 bytes e preserva o identificador como hexadecimal.

### Flash info

Aceita o intervalo de resposta permitido pelo codec e interpreta somente os primeiros quatro bytes como quantidade total de setores unsigned little-endian. O restante permanece sem interpretação nesta fase.

### Storage

Interpreta os quatro bytes como bitmask unsigned little-endian e expõe o primeiro bit definido, reproduzindo apenas o comportamento de seleção observado no utilitário de referência.

### Capability

Exige oito bytes e preserva o payload bruto em hexadecimal. Nenhum bit de capability recebe nome sem uma fonte e teste específicos.

## Segurança e robustez

- tamanhos truncados são rejeitados;
- valores de 32 bits são tratados como unsigned ao serem promovidos para `Long`;
- nenhum parser executa I/O;
- nenhum parser habilita comandos adicionais;
- bytes desconhecidos são preservados, não reinterpretados por heurística.
