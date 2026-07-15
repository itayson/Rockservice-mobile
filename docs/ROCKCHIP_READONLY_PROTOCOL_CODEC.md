# Codec de protocolo Rockchip somente leitura

## Objetivo

Esta camada codifica e valida estruturas de protocolo para consultas de metadados. O transporte Android permanece separado e condicionado ao fluxo de validação de hardware.

A implementação foi escrita em Kotlin a partir do comportamento público documentado pelo código-fonte do `rkdeveloptool` da organização `rockchip-linux`, observado no commit `304f073752fd25c854e1bcf05d8e7f925b1f4e14`.

## Operações allowlisted

O codec contém somente:

- `TEST_UNIT_READY` (`0x00`);
- `READ_FLASH_ID` (`0x01`);
- `READ_FLASH_INFO` (`0x1A`);
- `READ_CHIP_INFO` (`0x1B`);
- `READ_CAPABILITY` (`0xAA`).

`READ_STORAGE` (`0x2B`) foi removido do perfil validado depois que o teste físico apresentou timeout na fase de dados e a enumeração de operações do `rkdeveloptool` usada como referência não apresentou esse opcode entre as consultas de metadados.

## Subcódigos de Test Unit Ready

O codec não aceita subcódigos arbitrários. A allowlist contém apenas:

- `NONE` (`0x00`);
- `GET_USER_SECTOR_PROGRESS` (`0xF9`).

## CBW e CSW

O codec valida:

- tamanho de 31 bytes do Command Block Wrapper;
- assinatura CBW;
- tag da requisição;
- direção IN;
- tamanho do command block;
- opcode allowlisted;
- tamanho esperado ou intervalo limitado da resposta;
- tamanho de 13 bytes do Command Status Wrapper;
- assinatura CSW;
- correspondência exata de tag;
- status conhecido.

## Comportamento do probe físico

O probe mantém uma única sessão USB enquanto as transações permanecem sincronizadas. Qualquer timeout ou falha de transporte interrompe as consultas restantes e exige reconexão antes de uma nova tentativa.

## Limite de integração

A sessão USB somente leitura exige:

1. alvo selecionado de forma inequívoca;
2. VID Rockchip e topologia allowlisted;
3. permissão Android válida;
4. interface e endpoints revalidados;
5. timeouts finitos;
6. nenhuma operação fora da allowlist;
7. validação em hardware autorizado.
