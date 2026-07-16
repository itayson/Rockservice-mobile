# Planos de partições lógicas liblp

## Objetivo

Antes de extrair qualquer byte de uma partição lógica Android, o RockService Mobile converte as tabelas liblp já validadas em um plano imutável de leitura.

`AndroidSuperLogicalPartitionMapper` não abre arquivos e não lê payloads. Ele opera somente sobre `AndroidSuperMetadata` produzido pelo parser defensivo.

## Extents suportados

Neste gate são aceitos:

- `LINEAR`: referencia uma faixa física em um block device da tabela liblp;
- `ZERO`: representa uma faixa lógica preenchida com zeros.

Cada faixa é convertida de setores de 512 bytes para offsets e tamanhos em bytes usando aritmética verificada.

Targets desconhecidos ou não suportados são rejeitados.

## Validações

Para cada partição:

- `first_extent_index` e quantidade de extents devem formar uma faixa válida;
- todos os índices devem permanecer dentro da tabela de extents;
- `target_source` de um extent `LINEAR` deve apontar para um block device existente;
- setor de origem e contagem de setores não podem gerar overflow na conversão para bytes;
- o tamanho lógico total da partição é calculado pela soma verificada dos extents.

## Resultado

O plano contém:

- nome da partição;
- tamanho lógico total em bytes;
- lista ordenada de extents.

Um extent `LINEAR` contém:

- índice do block device de origem;
- offset de origem em bytes;
- comprimento em bytes.

Um extent `ZERO` contém apenas o comprimento lógico a materializar como zeros.

## Próximo gate

A futura extração deverá:

1. receber um plano já validado;
2. vincular cada block device a uma origem explicitamente selecionada;
3. validar o tamanho real de cada origem antes de copiar;
4. copiar extents `LINEAR` em streaming;
5. materializar extents `ZERO` sem leitura de origem;
6. calcular SHA-256 da saída;
7. tratar qualquer falha como possível destino parcial;
8. não inferir automaticamente arquivos externos ou caminhos de block devices.

Nenhuma escrita em hardware é introduzida por este componente.
