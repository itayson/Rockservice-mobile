# Laboratório de Firmware

## Objetivo

O Laboratório de Firmware transforma os parsers offline do projeto em um fluxo utilizável no aplicativo Android.

A tela inicial permite selecionar explicitamente um documento, executar análise somente leitura e exportar um relatório técnico em texto.

## Fluxo

1. o usuário toca em **Selecionar firmware**;
2. o seletor de documentos do Android entrega acesso ao documento escolhido;
3. o aplicativo lê nome e tamanho informados pelo provedor;
4. `FirmwareAnalyzer` calcula SHA-256 e identifica o formato por assinaturas conhecidas;
5. quando existe parser especializado, o documento é reaberto e validado estruturalmente;
6. a interface mostra uma prévia limitada;
7. o relatório completo pode ser salvo em um destino escolhido pelo usuário.

O aplicativo não pede permissão ampla de armazenamento para esse fluxo e não registra o URI do documento no relatório.

## Formatos especializados

### Android Sparse

São exibidos:

- versão;
- tamanho de bloco;
- blocos e chunks declarados;
- tamanho expandido estimado;
- contagem por tipo de chunk;
- descrição limitada dos chunks.

O payload não é expandido.

### Android Boot Image

São exibidos:

- header v0 a v4;
- page size e header size;
- tamanhos de kernel, ramdisk e seções opcionais;
- offsets e tamanhos alinhados das seções;
- tamanho mínimo estrutural da imagem.

Nenhuma seção é extraída.

### Android Super / liblp raw

São exibidos:

- versão da metadata;
- geometria validada;
- cópia primária ou backup utilizada;
- quantidade de slots;
- partições lógicas e tamanhos calculados;
- grupos;
- block devices;
- contagens de extents.

O fluxo tenta primeiro a cópia primária do slot 0. Se ela for estruturalmente inválida, tenta a cópia backup do mesmo slot.

Uma imagem `super.img` encapsulada em Android Sparse ainda não é convertida automaticamente para layout raw.

## Formatos identificados sem parser especializado

ZIP, ELF e ISO 9660 recebem identificação, SHA-256, tamanho e avisos, mas não são extraídos ou interpretados internamente nesta etapa.

Arquivos desconhecidos permanecem reportáveis como formato não reconhecido.

## Limites de apresentação

O núcleo limita a quantidade de entradas textuais geradas por seção. A interface mostra apenas uma prévia menor e informa quando existem linhas adicionais no relatório exportável.

Esses limites evitam que metadata com grande quantidade de partições ou chunks gere uma tela excessivamente pesada.

## Concorrência e lifecycle

- uma nova seleção cancela a análise anterior;
- análise e exportação executam fora da thread principal;
- o `ViewModel` mantém apenas o resultado e estado de apresentação;
- o relatório não armazena o URI original;
- o arquivo é reaberto para passes especializados em vez de ser carregado integralmente em memória.

## Fronteira de segurança

O Laboratório de Firmware não:

- grava firmware;
- monta filesystems;
- extrai payloads;
- acessa armazenamento físico de um dispositivo conectado;
- executa comandos Rockchip;
- habilita root;
- altera os gates de hardware de `#18` e `#19`.

Uma análise estrutural bem-sucedida não comprova compatibilidade do arquivo com um dispositivo específico.
