# Codec de protocolo Rockchip somente leitura

## Objetivo

Esta camada codifica e valida apenas estruturas de protocolo para consultas de metadados. Ela não abre dispositivos, não reivindica interfaces e não executa transferências USB.

A implementação foi escrita em Kotlin a partir do comportamento público documentado pelo código-fonte do `rkdeveloptool` da organização `rockchip-linux`, observado no commit `304f073752fd25c854e1bcf05d8e7f925b1f4e14`.

## Operações allowlisted

O codec contém somente:

- `TEST_UNIT_READY` (`0x00`);
- `READ_FLASH_ID` (`0x01`);
- `READ_FLASH_INFO` (`0x1A`);
- `READ_CHIP_INFO` (`0x1B`);
- `READ_STORAGE` (`0x2B`);
- `READ_CAPABILITY` (`0xAA`).

`READ_LBA`, leitura de SDRAM, leitura de eFuse e outros comandos que ampliariam o acesso a dados não fazem parte desta primeira allowlist.

## Subcódigos de Test Unit Ready

O protocolo de referência reutiliza o byte de subcódigo de `TEST_UNIT_READY` para comportamentos diferentes, incluindo operações destrutivas. Por isso o codec não aceita valores arbitrários.

A allowlist contém apenas:

- `NONE` (`0x00`);
- `GET_USER_SECTOR_PROGRESS` (`0xF9`).

Subcódigos associados a erase/format não existem na API pública deste codec e não podem ser codificados por ele.

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

## Limite de integração

O codec é propositalmente independente do transporte. Uma futura sessão USB somente leitura deve continuar separada e exigir, antes de transmitir qualquer CBW:

1. alvo selecionado de forma inequívoca;
2. VID Rockchip e topologia allowlisted;
3. permissão Android válida;
4. interface e endpoints revalidados;
5. timeouts finitos;
6. nenhuma operação fora da allowlist;
7. validação em hardware autorizado.

Até essa validação existir, o codec não é conectado ao painel do aplicativo nem executado contra um dispositivo físico.
