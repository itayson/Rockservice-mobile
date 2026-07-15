# Parser de metadados Android `super` / liblp

## Objetivo

Esta camada analisa metadados de partições dinâmicas Android em uma imagem `super` **raw**, sem mapear, montar, extrair ou gravar o conteúdo das partições lógicas.

A implementação foi baseada nas estruturas e validações públicas do AOSP em:

- `platform/system/core/fs_mgr/liblp/include/liblp/metadata_format.h`;
- `platform/system/core/fs_mgr/liblp/reader.cpp`;
- `platform/system/core/fs_mgr/liblp/utility.cpp`.

## Escopo atual

O parser lê:

- geometria primária e geometria de backup;
- um slot de metadata selecionado;
- cópia primária ou backup desse slot;
- header liblp versão 10.0, 10.1 ou 10.2;
- tabelas de partições;
- extents;
- grupos;
- block devices.

Ele calcula o tamanho lógico de cada partição a partir de seus extents e o total alocado em cada grupo.

## Layout inicial

O layout raw começa com:

1. 4096 bytes reservados;
2. bloco de geometria primária de 4096 bytes;
3. bloco de geometria backup de 4096 bytes;
4. slots de metadata primários;
5. slots de metadata backup;
6. área física utilizável pelos extents lógicos.

O offset de cada slot é calculado exclusivamente a partir da geometria validada.

## Geometria

A geometria possui magic `0x616C4467`, tamanho estrutural de 52 bytes e checksum SHA-256 calculado com o campo de checksum zerado.

O parser valida:

- magic;
- `struct_size` conhecido;
- checksum SHA-256;
- `metadata_max_size` positivo e alinhado a 512 bytes;
- quantidade de slots dentro do limite configurado;
- `logical_block_size` positivo e múltiplo de 512 bytes.

Quando as duas cópias de geometria são válidas, elas precisam ser idênticas nos campos interpretados. Se a primária estiver corrompida e a backup for válida, a backup é utilizada.

## Header e tabelas

O header usa magic `0x414C5030` e major version 10.

São aceitas minor versions:

- 10.0;
- 10.1;
- 10.2.

O parser exige o tamanho estrutural correspondente à versão e valida:

- SHA-256 do header com `header_checksum` zerado;
- SHA-256 do bloco completo de tabelas;
- `tables_size` dentro de `metadata_max_size`;
- descritores de tabela dentro dos limites;
- tamanhos exatos das entries conhecidas;
- tabelas contíguas e sem lacunas;
- limites configuráveis de quantidade de entries.

## Referências cruzadas

### Partições

Para cada partição são validados:

- nome ASCII válido;
- atributos compatíveis com a minor version;
- faixa de extents existente;
- índice de grupo existente;
- unicidade do nome.

O tamanho lógico é calculado pela soma dos setores de seus extents, usando setores de 512 bytes.

### Extents

São aceitos somente:

- target linear;
- target zero.

Um extent linear precisa:

- referenciar um block device existente;
- começar a partir de `first_logical_sector`;
- terminar dentro do tamanho físico declarado do block device.

Um extent zero não pode carregar target físico.

### Grupos

O parser soma o tamanho das partições pertencentes a cada grupo. Quando `maximum_size` é diferente de zero, a alocação calculada não pode exceder esse limite.

### Block devices

São validados:

- tamanho físico positivo e alinhado a setor;
- `first_logical_sector` dentro do dispositivo;
- nomes fixos corretamente terminados e sem bytes residuais após NUL.

## Limites de recursos

Valores padrão:

- até 64 GiB consumidos do fluxo de entrada;
- até 16 MiB por slot de metadata;
- até 32 slots;
- até 100.000 entries por tabela.

Esses limites são configuráveis e existem para proteção de recursos, não como parte da especificação do formato.

## Limites atuais

O parser não:

- expande uma imagem Android Sparse;
- busca automaticamente metadata dentro de um sparse `super.img`;
- mapeia partições lógicas em arquivos;
- lê o conteúdo apontado pelos extents;
- monta filesystems;
- modifica a metadata;
- grava qualquer dispositivo.

Uma `super.img` em formato Android Sparse precisa passar futuramente por uma etapa de expansão ou tradução segura antes deste parser raw.

## Fronteira de segurança

Metadata válida significa apenas que as estruturas liblp e suas referências internas passaram pelas validações implementadas.

Isso não comprova:

- que o conteúdo físico dos extents esteja íntegro;
- que a imagem pertença ao dispositivo selecionado;
- que todos os block devices estejam presentes no hardware real;
- que seja seguro gravar ou redimensionar qualquer partição.

Toda operação desta camada permanece offline e somente leitura.
