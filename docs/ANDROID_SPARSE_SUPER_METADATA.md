# Análise de metadata liblp em `super.img` Android Sparse

## Objetivo

O RockService Mobile pode analisar metadata de partições dinâmicas quando um `super.img` está encapsulado no formato Android Sparse sem expandir a imagem inteira em memória ou criar automaticamente um arquivo raw intermediário.

## Estratégia de prefixo limitado

`AndroidSparseSuperMetadataParser` usa duas passadas independentes sobre uma origem reabrível:

1. o parser estrutural Sparse valida a imagem e calcula seu tamanho raw expandido;
2. um decoder limitado materializa somente os primeiros `12288` bytes raw, suficientes para a área reservada e as duas cópias da geometria liblp;
3. a geometria é inspecionada apenas para planejar o tamanho mínimo do próximo prefixo;
4. uma segunda passagem decodifica somente até o final da metadata primária do slot 0;
5. o prefixo resultante é entregue ao `AndroidSuperMetadataParser` raw existente.

O parser raw continua sendo a fonte de verdade para:

- checksums da geometria;
- divergência entre cópias da geometria;
- offsets da metadata;
- checksum do header;
- checksum das tabelas;
- descritores e referências cruzadas;
- validação de partições, extents, grupos e block devices.

A inspeção preliminar da geometria serve apenas para calcular um limite seguro de decodificação.

## Limites

Por padrão:

- metadata por slot: no máximo `64 MiB`;
- prefixo raw decodificado: no máximo `128 MiB`.

A análise falha quando o tamanho necessário para alcançar a metadata excede o limite configurado.

A imagem Sparse inteira nunca é materializada em memória por este adaptador.

## Decoder de prefixo

`AndroidSparseRawPrefixDecoder` reutiliza `AndroidSparseImageExpander`, portanto a semântica de `RAW`, `FILL`, `DONT_CARE` e validações estruturais permanece centralizada.

Um `OutputStream` interno captura exatamente o prefixo solicitado e interrompe deliberadamente a expansão quando o limite é atingido. O resultado parcial é usado somente como prefixo estrutural de leitura; nenhum arquivo é gravado.

## Comportamento no Laboratório de Firmware

Quando o formato externo é `ANDROID_SPARSE`:

- o relatório Sparse normal continua sendo gerado;
- o adaptador procura geometria liblp no prefixo raw;
- se nenhuma assinatura de geometria for encontrada, a imagem continua sendo tratada como Sparse comum;
- se um `super` válido for detectado, a seção estrutural liblp é adicionada ao mesmo relatório.

O formato externo do relatório permanece `ANDROID_SPARSE`, porque esse é o encapsulamento real do arquivo analisado.

## Fora de escopo

Este gate não:

- expande o `super.img` inteiro automaticamente;
- cria arquivos temporários raw;
- extrai partições lógicas;
- monta filesystems;
- escreve em hardware;
- altera a imagem de origem.

O próximo gate é mapear extents de partições lógicas de forma defensiva antes de permitir qualquer extração de payload.
