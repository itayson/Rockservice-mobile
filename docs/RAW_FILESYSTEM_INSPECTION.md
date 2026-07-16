# Inspeção estrutural limitada de filesystems raw

## Objetivo

`RawFilesystemInspector` reconhece evidências estruturais básicas de imagens raw usando somente um prefixo limitado. O componente não monta o filesystem, não percorre diretórios e não extrai arquivos.

A classificação é uma **identificação estrutural conservadora**, não uma autenticação do artefato. O inspetor calcula SHA-256 apenas dos bytes efetivamente inspecionados para tornar a evidência do prefixo reproduzível. A integridade/proveniência da imagem completa deve continuar usando o SHA-256 integral calculado pelo fluxo principal do Laboratório de Firmware.

## Formatos reconhecidos

### ext4

- superblock esperado a partir do offset `1024`;
- magic `0xEF53` no campo correspondente;
- `log_block_size` validado antes do cálculo do block size;
- ao menos uma feature `INCOMPAT` específica de ext4 deve estar presente;
- um superblock compatível apenas com a família ext2/ext3 não é rotulado como ext4;
- block size limitado a potência de dois até `64 KiB`.

A política é deliberadamente conservadora: uma imagem ext4 criada sem qualquer feature incompatível específica pode permanecer como `UNKNOWN` em vez de gerar um falso positivo.

### F2FS

- superblock inspecionado a partir do offset `1024`;
- magic `0xF2F52010`;
- `log_blocksize` é aceito entre `12` e `16` (`4 KiB` a `64 KiB`) para cobrir geometrias coerentes com diferentes tamanhos de página;
- `log_blocks_per_seg` deve ser `9`;
- `log_sectorsize` deve ser compatível com o bloco declarado;
- `log_sectorsize + log_sectors_per_block` deve corresponder exatamente a `log_blocksize`;
- `segs_per_sec` e `secs_per_zone` devem ser positivos.

O formato F2FS usa bloco igual ao tamanho de página suportado pelo kernel que fará o mount; portanto, reconhecer estruturalmente uma imagem de `16 KiB` não significa que um kernel de página `4 KiB` conseguirá montá-la. Layouts incoerentes permanecem como `UNKNOWN`; o inspetor não tenta adaptar nem reinterpretar a imagem.

### EROFS

- superblock inspecionado a partir do offset `1024`;
- magic `0xE0F5E1E2`;
- `blkszbits` validado entre `9` e `16`, mantendo um teto conservador de `64 KiB` para o detector genérico;
- a compatibilidade real de montagem continua dependente do `PAGE_SHIFT` do kernel alvo.

### SquashFS

- magic inicial `hsqs` (`0x73717368` em little-endian);
- tamanho de bloco deve ser potência de dois entre `4 KiB` e `1 MiB`.

## Limites e evidência

O prefixo padrão é de `4096` bytes e nunca cresce automaticamente.

A inspeção retorna somente:

- tipo reconhecido;
- quantidade de bytes inspecionados;
- tamanho de bloco, quando validado;
- SHA-256 do prefixo efetivamente lido;
- detalhe textual sanitizado.

O SHA-256 do prefixo **não** é apresentado como digest da imagem completa e não é usado como âncora de confiança. Exigir um digest pré-conhecido para classificar a estrutura criaria uma dependência circular; quando autenticidade é necessária, o chamador deve comparar o SHA-256 integral da imagem com uma origem confiável.

Campos estruturais absurdos falham de forma fechada em vez de serem aceitos como evidência válida. Falhas de `InputStream` são propagadas com contexto do limite e offset de inspeção.

## Fora de escopo

Este gate não:

- monta filesystems;
- interpreta inodes ou diretórios;
- extrai arquivos;
- executa código nativo;
- escreve na imagem;
- escreve em hardware;
- afirma autenticidade com base em um prefixo parcial.

A integração com o Laboratório de Firmware deve ocorrer separadamente para preservar a testabilidade do núcleo.
