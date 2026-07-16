# Extração controlada de seções Android Boot Image

## Objetivo

`AndroidBootSectionExtractor` copia um payload individual de uma Android Boot Image usando um `AndroidBootImageMetadata` previamente validado pelo parser estrutural.

O componente é somente leitura em relação à origem e grava exclusivamente no `OutputStream` fornecido pelo chamador.

## Seções permitidas

Neste gate, podem ser extraídas somente seções de payload presentes exatamente uma vez no metadata:

- `KERNEL`;
- `RAMDISK`;
- `SECOND_STAGE`;
- `RECOVERY_DTBO`;
- `DTB`;
- `BOOT_SIGNATURE`.

A seção `HEADER` é rejeitada explicitamente.

A extração copia apenas `sizeBytes`. Bytes de padding até `paddedSizeBytes` não são exportados.

## Vínculo com a análise anterior

O chamador deve fornecer o SHA-256 completo obtido durante a análise da imagem.

Durante a extração, o componente lê a origem inteira em streaming e calcula novamente o SHA-256. O resultado só é considerado válido se o hash atual for idêntico ao hash esperado.

Isso detecta mudanças na origem entre a análise e a extração.

Como a comparação do hash completo só pode ocorrer ao final da leitura, uma origem alterada pode produzir dados no destino antes da falha final. A camada de UI deve tratar qualquer falha como potencial destino parcial e orientar sua remoção.

## Limites

A implementação aplica:

- limite máximo para bytes lidos da origem;
- limite máximo para o tamanho da seção extraída;
- buffer fixo de transferência;
- aritmética verificada para o final da seção;
- verificação de que a seção permanece dentro do layout mínimo validado.

A saída não é carregada integralmente em memória.

## Evidência produzida

O relatório de sucesso contém:

- tipo da seção;
- offset validado;
- bytes extraídos;
- bytes totais lidos da origem;
- SHA-256 completo da origem;
- SHA-256 do payload extraído.

## Integração Android futura

A UI deve:

1. manter a origem escolhida explicitamente pelo operador;
2. reabrir e parsear a origem para obter metadata atualizado;
3. solicitar um destino explícito via Storage Access Framework;
4. impedir origem e destino iguais;
5. executar em `Dispatchers.IO`;
6. mostrar aviso de destino parcial em qualquer falha após abertura da saída;
7. registrar somente metadados sanitizados no log técnico.

## Fora de escopo

Este componente não:

- modifica a imagem de origem;
- extrai todas as seções automaticamente;
- descompacta kernel ou ramdisk;
- monta filesystems;
- repacota ou assina boot images;
- escreve em hardware.
