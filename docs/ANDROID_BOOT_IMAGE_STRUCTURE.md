# Parser estrutural Android Boot Image

## Objetivo

Esta camada interpreta e valida a estrutura declarada de imagens Android Boot com headers v0, v1, v2, v3 e v4 sem extrair, modificar, assinar ou gravar qualquer payload.

A implementação foi baseada no layout público mantido pelo AOSP em `platform/system/tools/mkbootimg/include/bootimg/bootimg.h` e na documentação oficial de versionamento de boot image headers.

## Magic e detecção de versão

Todas as versões suportadas usam a magic ASCII `ANDROID!` nos primeiros 8 bytes.

O campo `header_version` permanece no offset 40 nos layouts v0-v4, permitindo detectar a versão a partir de um prefixo mínimo antes de interpretar o restante do header.

O parser aceita somente as versões 0, 1, 2, 3 e 4. Valores fora dessa faixa são rejeitados.

## Headers legacy — v0, v1 e v2

Os layouts legacy usam `page_size` declarado no próprio header.

Tamanhos estruturais mínimos usados pelo parser:

- v0: 1632 bytes;
- v1: 1648 bytes;
- v2: 1660 bytes.

A página inicial contém o header. As seções seguintes são posicionadas em fronteiras de `page_size`.

### v0

Ordem estrutural:

1. header;
2. kernel;
3. ramdisk;
4. second stage, quando presente.

### v1

Adiciona:

- tamanho de `recovery_dtbo`;
- offset absoluto declarado de `recovery_dtbo`;
- `header_size`.

Quando `recovery_dtbo` existe, o parser exige que o offset declarado corresponda exatamente ao offset calculado pelo layout alinhado.

### v2

Mantém os campos de v1 e adiciona uma seção DTB após `recovery_dtbo`.

## Headers modernos — v3 e v4

Os layouts v3 e v4 usam página fixa de 4096 bytes.

Tamanhos estruturais mínimos:

- v3: 1580 bytes;
- v4: 1584 bytes.

### v3

Ordem estrutural:

1. página de header de 4096 bytes;
2. kernel alinhado a 4096 bytes;
3. ramdisk alinhado a 4096 bytes.

### v4

Mantém o layout v3 e adiciona `boot_signature` após o ramdisk, também representado como seção alinhada no layout validado.

## Validações defensivas

Antes de aceitar a estrutura, o parser verifica:

- magic `ANDROID!`;
- versão de header suportada;
- tamanho estrutural mínimo da versão;
- `header_size` compatível com a versão quando o campo existe;
- header contido na página correspondente;
- `page_size` legacy positivo e abaixo do limite configurável de recursos;
- aritmética de offsets e alinhamentos com checagem de overflow;
- offset declarado de `recovery_dtbo` consistente com o layout calculado;
- offsets `uint64` representáveis de forma segura no domínio do parser;
- tamanho mínimo total dentro dos limites configurados;
- presença física de todas as páginas e seções declaradas, detectando truncamento.

## Uso de memória

O parser lê apenas o header em memória. Kernel, ramdisk, second stage, recovery DTBO, DTB e boot signature são percorridos por streaming e descartados durante a validação estrutural.

O consumo de memória não cresce proporcionalmente ao tamanho dos payloads.

## Limites padrão

- até 64 GiB consumidos da entrada;
- até 64 GiB de layout mínimo declarado;
- `page_size` legacy de até 1 MiB.

Os limites são controles de recursos configuráveis e não fazem parte da definição do formato Android Boot.

## Fronteira de segurança

Uma imagem aceita pelo parser é apenas estruturalmente coerente com um dos layouts implementados.

Isso **não** significa que:

- kernel ou ramdisk sejam confiáveis;
- a imagem pertença ao dispositivo selecionado;
- endereços de carregamento sejam apropriados para um SoC específico;
- AVB ou qualquer assinatura tenha sido validada;
- DTB ou recovery DTBO sejam compatíveis com a placa;
- a imagem possa ser gravada ou inicializada com segurança.

A análise permanece completamente offline. Não acessa USB, root, bootloader, Loader/Maskrom nem armazenamento físico de um dispositivo.

## Próximos passos seguros

Evoluções futuras podem adicionar interpretação limitada de metadados internos e identificação de compressão dos payloads, desde que continuem separadas de qualquer fluxo de gravação.

Extração e empacotamento devem possuir limites próprios, caminhos de destino controlados e validações adicionais. Gravação permanece bloqueada pelos gates da Fase 5 do roadmap.
