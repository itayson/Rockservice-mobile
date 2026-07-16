# RockService Mobile

Aplicativo Android open source para diagnóstico, análise e transformação controlada de firmware e manutenção **autorizada** de equipamentos Rockchip, projetado para operar **100% offline em runtime**.

O aplicativo não depende de ADB, internet, APIs remotas, telemetria ou serviços externos. As entradas de dados permitidas são dispositivos físicos conectados por USB/OTG, arquivos escolhidos explicitamente pelo usuário via Storage Access Framework e recursos estáticos empacotados no APK.

## Estado atual — 0.2.0-alpha01

A versão `0.2.0-alpha01` é uma prévia de desenvolvimento utilizável. Operações destrutivas de hardware continuam deliberadamente desativadas até existirem evidências físicas e gates de segurança suficientes.

### Laboratório de Firmware

Implementado e coberto por testes/CI:

- seleção explícita de documentos pelo Storage Access Framework, sem permissão ampla de armazenamento;
- identificação de formatos e SHA-256 integral em streaming;
- Android Sparse: parser estrutural defensivo e expansão streaming de `RAW`, `FILL`, `DONT_CARE` e `CRC32`;
- destino explícito para expansão Sparse, proteção direta contra origem=destino e aviso de saída parcial;
- Android Boot Image v0-v4: parser de layout e extração individual de payloads não vazios;
- revalidação do SHA-256 completo da Boot Image durante a extração;
- `super.img` raw/liblp: geometria, checksums, tabelas, grupos, block devices e referências cruzadas;
- análise de metadata liblp diretamente de `super.img` Android Sparse por decodificação limitada de prefixo;
- mapeamento imutável de partições lógicas com extents `LINEAR` e `ZERO`;
- exportação streaming de uma partição lógica por vez a partir de `super.img` raw, com SHA-256 da saída e contagem exata;
- inspeção estrutural limitada de imagens raw ext4, F2FS, EROFS e SquashFS, sem montagem;
- relatório técnico exportável e log estruturado/sanitizado em JSONL.

### USB Host e Rockchip somente leitura

Implementado:

- enumeração Android USB Host e monitoramento de attach/detach;
- seleção explícita e revalidação do alvo por identidade USB;
- solicitação controlada de permissão Android;
- inspeção passiva de interfaces/endpoints e descritores;
- transporte Android Rockchip real somente leitura, serializado e fail-closed;
- baseline ativo validado em hardware autorizado para `TEST_UNIT_READY`, `READ_CHIP_INFO`, `READ_FLASH_ID` e `READ_FLASH_INFO`;
- leitura LBA limitada localmente e prova controlada de exatamente um setor no LBA 0;
- plano e integração interna para inspeção fixa de dois setores LBA 0–1 e assinaturas MBR/GPT;
- proteção contra resultados obsoletos, timeouts, concorrência e sessões que exigem reconexão.

A expansão para leituras físicas maiores continua bloqueada pelos gates de hardware `#18` e `#35`.

### Runtime totalmente offline

A arquitetura do produto estabelece:

- nenhuma permissão `INTERNET` no manifesto de produção;
- nenhuma permissão `ACCESS_NETWORK_STATE` ou equivalente necessária ao runtime;
- nenhuma dependência de ADB ou ADB Sync;
- nenhuma API HTTP, WebSocket, SSH, FTP, WebView remoto ou cliente de rede no runtime;
- nenhum download automático de firmware, loader ou configuração;
- análise, backup, restauração e transformação executados localmente;
- política de CI que falha quando permissões ou bibliotecas de rede proibidas são reintroduzidas.

A política completa está documentada em `docs/OFFLINE_ARCHITECTURE.md`.

## Limites deliberados

Ainda não são considerados concluídos ou habilitados:

- matriz ampla de compatibilidade física Rockchip (`#18`);
- validação física final da inspeção fixa LBA 0–1 (`#35`);
- backup físico genérico de NAND, SPI NAND, eMMC ou partições Rockchip;
- gravação, erase, reset ou operações destrutivas;
- Loader/Maskrom genérico sem combinação de hardware/loader explicitamente validada;
- carregamento automático de firmware ou loader a partir da internet;
- extração direta de partições lógicas de um `super.img` ainda sparse sem expansão explícita para RAW;
- release de produção assinada até os secrets/keystore e gates administrativos de `#20` estarem configurados.

O APK de debug pode ser compilado e instalado para uso e validação. Ele é assinado pela chave de debug do Android e não deve ser confundido com uma release de produção assinada.

## Modelo de segurança

- nenhuma operação de firmware é iniciada automaticamente ao selecionar um arquivo;
- transformações e exportações exigem destino escolhido explicitamente;
- resultados antigos são invalidados quando a origem ou o alvo muda;
- logs sanitizados não armazenam URIs de documentos ou conteúdo integral de firmware;
- broadcasts USB apenas disparam nova enumeração; dados de broadcast não autorizam um alvo;
- operações Rockchip de escrita permanecem ausentes/desativadas por padrão;
- o build mantém `REAL_USB_WRITE_ENABLED=false` tanto em debug quanto em release;
- compatibilidade de firmware nunca é inferida apenas por uma assinatura estrutural;
- o runtime não possui canal de rede para transmitir diagnósticos, firmware ou identificadores;
- o gate `scripts/verify-offline-runtime.sh` protege a política offline no CI.

## Compilação

Requisitos: JDK 17, Android SDK 36, CMake 3.22.1 e NDK configurado.

```bash
./gradlew --no-daemon test :app:assembleDebug lint
```

O CI também executa a verificação de runtime offline, CodeQL, Gitleaks e gates de supply chain. O Gradle Wrapper 8.13 é versionado com verificações de integridade.

## Cadeia de release

A infraestrutura de build de release exige assinatura real, valida o APK com `apksigner`, gera checksum/SBOM/provenance e separa build da publicação. A ativação operacional permanece bloqueada por `#20`; nenhum segredo de assinatura é armazenado no repositório.

## Documentação principal

- `docs/OFFLINE_ARCHITECTURE.md`
- `docs/ARCHITECTURE.md`
- `docs/FIRMWARE_LAB.md`
- `docs/ANDROID_USB_HOST.md`
- `docs/ROCKCHIP_READONLY_PROTOCOL_CODEC.md`
- `docs/ROCKCHIP_METADATA_PARSERS.md`
- `docs/ANDROID_SPARSE_STRUCTURE.md`
- `docs/ANDROID_SPARSE_SUPER_METADATA.md`
- `docs/ANDROID_BOOT_IMAGE_STRUCTURE.md`
- `docs/ANDROID_SUPER_METADATA.md`
- `docs/ROADMAP.md`
- `docs/THREAT_MODEL.md`
- `SECURITY.md`
