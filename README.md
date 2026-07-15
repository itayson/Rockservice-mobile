# RockService Mobile

Aplicativo Android open source para diagnóstico, análise de firmware e manutenção **autorizada** de equipamentos Rockchip, respeitando os limites do Android, do hardware e das evidências de compatibilidade disponíveis.

## Estado atual

`0.1.0-alpha01` continua sendo uma versão de desenvolvimento, mas a fundação já ultrapassou o bootstrap exclusivamente simulado.

Implementado e coberto por testes/CI:

- aplicativo Android com Jetpack Compose;
- detecção de capacidades do dispositivo host;
- identificação inicial de formatos de firmware por magic bytes;
- SHA-256 em streaming e limites para análise de arquivos;
- parser estrutural Android Sparse com validação de headers, chunks, limites e contabilidade de blocos, sem expansão de payload;
- parser estrutural Android Boot Image v0-v4 com validação de páginas, seções, offsets e truncamento, sem extração de payload;
- parser raw de metadados Android `super`/liblp com validação de geometria, SHA-256, tabelas e referências cruzadas, sem mapear partições;
- backend USB simulado com validações de alvo, limites, timeout e lifecycle;
- backend Android USB Host em modo somente leitura para enumeração e descritores USB;
- solicitação controlada de permissão USB e revalidação do alvo;
- inspeção passiva de interfaces e endpoints;
- identificação conservadora do vendor ID Rockchip `0x2207`;
- probe passivo de topologia bulk sem afirmar Loader ou Maskrom;
- painel de diagnóstico USB com atualização manual;
- monitoramento de attach/detach com re-enumeração completa;
- seleção explícita e reconciliação de um único alvo USB;
- codec Rockchip isolado para consultas de metadados allowlisted;
- sessão read-only abstrata e serializada, sem transporte Android real;
- parsers defensivos para chip info, flash ID/info, storage e capability;
- CI, CodeQL, Gitleaks e gates de supply chain;
- política de confirmação para operações destrutivas futuras;
- módulos NDK isolados para evolução posterior.

### Validação física

O backend Android USB Host e as camadas passivas acima estão implementados e cobertos por testes automatizados, mas **o suporte em hardware Rockchip físico ainda não foi comprovado como matriz de compatibilidade**. Essa validação está rastreada em `#18` e é pré-requisito para conectar o transporte Rockchip real ao dispositivo.

## Limites atuais

Ainda **não** estão implementados ou habilitados:

- expansão ou extração de imagens Android Sparse;
- extração ou modificação de payloads de Android Boot Images;
- expansão automática de `super.img` sparse para análise liblp;
- mapeamento ou extração de partições lógicas a partir de extents `super`;
- transporte Rockchip real por `bulkTransfer()`;
- identificação ativa de Loader ou Maskrom;
- leitura de NAND, SPI NAND, eMMC ou partições via protocolo Rockchip;
- backup físico de armazenamento;
- gravação, erase, reset ou download de loader;
- root;
- bypass de autenticação ou bootloader;
- release de produção assinada e publicada automaticamente.

A validação física do USB Host está rastreada em `#18`. O transporte Rockchip real somente leitura está bloqueado por essa validação em `#19`. A cadeia de release assinada/SBOM está rastreada em `#20`.

## Modelo de segurança

Broadcasts USB servem apenas como sinal para uma nova enumeração. Nenhum dispositivo é autorizado diretamente por dados recebidos de broadcast.

O codec Rockchip não expõe comandos de escrita e não representa subcódigos de erase/format. A sessão de protocolo permanece desconectada do hardware até existir validação física suficiente.

Os parsers Android Sparse, Android Boot e `super`/liblp validam apenas estruturas declaradas e aplicam limites explícitos. Eles não provam compatibilidade da imagem com qualquer dispositivo e não habilitam gravação.

## Compilação

Requisitos: JDK 17, Android SDK 36, CMake 3.22.1 e NDK configurado.

```bash
./gradlew --no-daemon test :app:assembleDebug lint
```

O Gradle Wrapper 8.13 oficial está versionado no repositório. A distribuição possui SHA-256 fixado em `gradle-wrapper.properties` e o CI também valida o checksum do `gradle-wrapper.jar` antes de executá-lo.

## Documentação

Consulte:

- `docs/FEASIBILITY.md`;
- `docs/ARCHITECTURE.md`;
- `docs/ANDROID_USB_HOST.md`;
- `docs/ROCKCHIP_PASSIVE_PROBE.md`;
- `docs/USB_DIAGNOSTICS_DASHBOARD.md`;
- `docs/USB_TARGET_SELECTION.md`;
- `docs/ROCKCHIP_READONLY_PROTOCOL_CODEC.md`;
- `docs/ROCKCHIP_READONLY_SESSION.md`;
- `docs/ROCKCHIP_METADATA_PARSERS.md`;
- `docs/ANDROID_SPARSE_STRUCTURE.md`;
- `docs/ANDROID_BOOT_IMAGE_STRUCTURE.md`;
- `docs/ANDROID_SUPER_METADATA.md`;
- `docs/ROADMAP.md`;
- `docs/THREAT_MODEL.md`;
- `SECURITY.md`.
