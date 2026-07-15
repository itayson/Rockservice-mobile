# Parser estrutural Android Sparse

## Objetivo

Esta camada interpreta e valida a estrutura de imagens no formato Android Sparse sem expandir, extrair, montar ou gravar o conteúdo.

O parser foi implementado a partir da especificação efetiva mantida pelo AOSP em `platform_system_core/libsparse`, especialmente `sparse_format.h` e o fluxo de validação de `sparse_read.cpp`.

## Estrutura reconhecida

O formato usa magic little-endian `0xED26FF3A` e um header base de 28 bytes com:

- versão major e minor;
- tamanho do header do arquivo;
- tamanho do header de chunk;
- tamanho do bloco;
- total de blocos da imagem expandida;
- total de chunks da imagem sparse;
- checksum declarado.

A versão major suportada é `1`. Versões minor superiores são aceitas, e headers maiores que os tamanhos base são avançados de forma controlada.

## Tipos de chunk

O parser reconhece somente os quatro tipos definidos pelo formato AOSP:

- `RAW` (`0xCAC1`): payload deve ter exatamente `chunk_sz × block_size` bytes;
- `FILL` (`0xCAC2`): payload deve conter exatamente 4 bytes;
- `DONT_CARE` (`0xCAC3`): não pode conter payload;
- `CRC32` (`0xCAC4`): payload deve conter exatamente 4 bytes e não acrescenta blocos à saída.

Tipos desconhecidos são rejeitados.

## Validações defensivas

Antes de aceitar a estrutura, a implementação verifica:

- magic correta;
- versão major suportada;
- `file_hdr_sz >= 28`;
- `chunk_hdr_sz >= 12`;
- tamanho de bloco positivo e múltiplo de 4;
- limite configurável de quantidade de chunks;
- limite configurável de bytes consumidos da entrada;
- limite configurável do tamanho expandido declarado;
- `total_sz >= chunk_hdr_sz` para cada chunk;
- payload exato conforme o tipo;
- aritmética com checagem de overflow;
- ausência de truncamento em headers e payloads;
- soma final dos blocos de saída igual a `total_blks`.

## Uso de memória

Payloads `RAW` são descartados por streaming durante a análise estrutural. O parser não aloca um buffer proporcional ao tamanho do chunk RAW nem ao tamanho expandido da imagem.

A memória cresce principalmente com a lista de metadados dos chunks, por isso existe um limite explícito de quantidade de chunks.

## Limites padrão

- no máximo `100.000` chunks;
- no máximo `64 GiB` consumidos da entrada;
- no máximo `16 TiB` de tamanho expandido declarado.

Esses limites são configuráveis pelo chamador e não representam garantia de que uma imagem dentro deles seja segura para gravação.

## Fronteira de segurança

Uma análise estrutural bem-sucedida significa apenas que a sequência de headers e chunks é coerente com as regras implementadas.

Ela **não** significa que:

- a imagem pertence ao dispositivo selecionado;
- o conteúdo de filesystem é confiável;
- o checksum declarado foi validado contra todos os dados expandidos;
- a imagem pode ser gravada com segurança;
- partições, offsets ou tamanhos físicos são compatíveis com um alvo real.

Qualquer futura expansão ou extração deve continuar usando streaming, limites explícitos, destino controlado e validação de espaço disponível. Qualquer futura gravação permanece sujeita aos gates da Fase 5 do roadmap.

## Estado atual

O parser é totalmente offline. Não acessa USB, root, armazenamento físico de um dispositivo, Loader/Maskrom nem qualquer backend de gravação.
