# Expansão Android Sparse em streaming

## Objetivo

`AndroidSparseImageExpander` converte uma imagem Android Sparse em fluxo raw sem carregar a imagem de entrada ou a saída expandida inteira em memória.

A API recebe explicitamente um `InputStream` e um `OutputStream`. O chamador mantém a propriedade dos dois streams; o expander não os fecha.

## Semântica dos chunks

A implementação segue a semântica de `libsparse` do AOSP:

- `RAW`: copia exatamente `chunk_sz * block_size` bytes da entrada para a saída;
- `FILL`: repete os 4 bytes do valor de preenchimento por toda a faixa expandida;
- `DONT_CARE`: escreve zeros explicitamente para produzir uma saída raw determinística em qualquer tipo de `OutputStream`;
- `CRC32`: não produz bytes e valida o CRC32 acumulado da saída expandida até aquele ponto.

No cálculo do CRC32, regiões `DONT_CARE` são tratadas como zeros, conforme o formato de referência.

## Limites defensivos

Antes da expansão, o header é validado e o tamanho total expandido é calculado com aritmética verificada.

Os limites configuráveis incluem:

- quantidade máxima de chunks;
- bytes máximos consumidos da entrada;
- bytes máximos da imagem expandida;
- tamanho fixo do buffer de transferência.

A expansão aborta quando:

- o magic ou major version não são suportados;
- headers são menores que o formato base;
- o tamanho de bloco é inválido;
- a quantidade de chunks excede o limite;
- o tamanho expandido excede o limite;
- um payload possui tamanho incompatível com seu tipo;
- a entrada termina antes do esperado;
- os chunks excedem ou não completam a contagem declarada de blocos;
- um chunk `CRC32` diverge do CRC acumulado;
- o checksum não-zero do header diverge do CRC final.

## Memória

O uso de memória de trabalho é limitado por buffers fixos e pequenos objetos de metadados. A implementação não aloca um array proporcional ao tamanho expandido.

`FILL` usa um buffer preenchido com o padrão de 4 bytes. `DONT_CARE` usa um buffer de zeros. `RAW` é copiado em partes.

## Evidência produzida

O relatório final inclui:

- tamanho expandido;
- bytes consumidos da imagem sparse;
- quantidade de chunks;
- SHA-256 da saída raw;
- CRC32 final;
- quantidade de chunks CRC32 validados;
- indicação de validação do checksum do header.

O relatório não retém os bytes expandidos.

## Integração com a interface

A integração Android deve ocorrer em uma etapa separada. Ela precisa:

1. exigir seleção explícita do arquivo sparse de origem;
2. exigir seleção explícita de um novo destino via Storage Access Framework;
3. executar a expansão em `Dispatchers.IO`;
4. deixar claro que falhas podem produzir um destino parcial;
5. nunca sobrescrever silenciosamente a origem;
6. apresentar tamanho, SHA-256 e resultado de checksum ao operador;
7. registrar somente metadados sanitizados no log técnico.

## Fora de escopo

Este componente não:

- monta filesystems;
- interpreta partições dentro da imagem raw;
- reempacota para sparse;
- escreve em hardware;
- escolhe caminhos ou arquivos automaticamente.
