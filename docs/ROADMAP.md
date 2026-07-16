# Roadmap

## Fase 0 — Pesquisa e protótipos

**Estado: fundação concluída.**

Entregue:

- matriz inicial de viabilidade;
- arquitetura modular;
- baseline de segurança;
- Gradle/CI/CodeQL/supply chain;
- estudo de USB Host e protocolo Rockchip público;
- separação explícita entre código passivo, codec e transporte físico.

## Fase 1 — Base do aplicativo

**Estado: base operacional concluída; evolução contínua por feature.**

Entregue:

- app Compose;
- detecção de capacidades;
- painel de diagnóstico USB;
- seleção explícita de alvo;
- lifecycle USB e attach/detach;
- ViewModels/coordenadores para remover estado operacional das `Activity`;
- relatório sanitizado de validação de hardware com exportação controlada;
- invalidação de jobs e resultados obsoletos ao trocar alvo/origem;
- log técnico estruturado, sanitizado e limitado em memória;
- tela de consulta, limpeza e exportação manual em JSONL sem permissão ampla de armazenamento;
- integração de eventos técnicos nos fluxos principais de firmware e Rockchip.

Pendente apenas quando surgir requisito concreto:

- persistência local estruturada além dos arquivos privados estritamente necessários;
- novos casos de uso compartilhados para futuras operações críticas.

## Fase 2 — Laboratório de firmware

**Estado: núcleo de análise e extração controlada implementado.**

Entregue:

- identificação de formatos por assinaturas;
- leitura em streaming e SHA-256 integral;
- limites para arquivos e headers truncados;
- parser estrutural Android Sparse com validação de headers, chunks, limites e contabilidade de blocos;
- expansão Android Sparse em streaming com `RAW`, `FILL`, `DONT_CARE`, CRC32, SHA-256 e limites defensivos;
- fluxo Android explícito para selecionar destino da expansão Sparse, bloquear origem=destino e sinalizar saída parcial;
- parser Android Boot Image v0-v4 com layout, alinhamentos, offsets e truncamento;
- extração streaming/hash-bound de payloads Boot Image sem padding;
- tela dedicada para revalidar a origem e exportar uma seção Boot Image por vez, com `HEADER` bloqueado;
- cancelamento cooperativo e serialização das extrações Boot Image;
- parser raw de metadata Android `super`/liblp com geometria, checksums, tabelas e referências cruzadas;
- análise de metadata liblp diretamente de `super.img` Android Sparse por decodificação limitada de prefixo;
- mapper imutável de partições lógicas com extents `LINEAR`/`ZERO` e aritmética fail-closed;
- exportador streaming de partições lógicas com SHA-256, short-read fail-closed e checkpoints;
- tela Android para exportar uma partição lógica por vez de `super.img` raw com revalidação SHA-256 antes/depois;
- inspeção estrutural limitada de ext4, F2FS, EROFS e SquashFS sem montagem;
- integração das evidências de filesystem raw ao relatório técnico.

Próximas evoluções:

1. permitir leitura de ranges diretamente de um `super.img` sparse sem exigir expansão completa para RAW, mantendo limites e validação estrutural;
2. aprofundar parsers de filesystems somente quando houver caso de uso de leitura segura de arquivos internos;
3. implementar empacotamento/reempacotamento apenas com formato de saída claramente definido, validação round-trip e nenhum destino implícito.

## Fase 3 — Runtime offline e diagnóstico local

**Estado: migração em andamento.**

Direção oficial:

- remover completamente ADB, ADB Sync e autenticação ADB do produto;
- não declarar `INTERNET`, `ACCESS_NETWORK_STATE` ou permissões equivalentes no runtime;
- não depender de HTTP, WebSocket, SSH, FTP, WebView remoto ou APIs externas;
- usar apenas USB/OTG, arquivos selecionados via SAF e recursos estáticos empacotados;
- manter logs, relatórios, hashes e análises exclusivamente locais;
- falhar no CI se permissões ou bibliotecas de rede proibidas forem reintroduzidas.

Entregue nesta migração:

- remoção da superfície ADB da UI e do manifesto;
- encerramento da linha de desenvolvimento ADB Sync sem merge;
- remoção do núcleo de sessão, protocolo, handshake, diagnóstico e transporte USB ADB;
- remoção/aposentadoria da suíte de testes exclusiva de ADB;
- documentação `docs/OFFLINE_ARCHITECTURE.md`;
- gate `scripts/verify-offline-runtime.sh` integrado ao CI.

Próximos gates:

1. concluir a remoção de referências documentais e marcadores históricos ADB;
2. validar o APK e o grafo Gradle sem dependências de rede;
3. manter a política offline como requisito de release;
4. concentrar novos diagnósticos em USB/OTG Rockchip, arquivos locais e evidências coletadas localmente.

## Fase 4 — Rockchip somente leitura

**Estado: em andamento. Transporte real, baseline ativo e primeira leitura LBA limitada já foram validados em hardware autorizado; expansão continua protegida por gates físicos.**

Entregue:

- enumeração Android USB Host;
- permissão e revalidação de alvo;
- leitura de descritores USB brutos;
- inspeção passiva de interfaces/endpoints;
- probe conservador por VID/topologia;
- seleção de alvo e monitoramento attach/detach;
- codec CBW/CSW com allowlist de consultas de metadados;
- parsers defensivos de chip, flash e storage;
- transporte Android Rockchip real somente leitura com sessão serializada e fechamento controlado;
- baseline ativo validado em hardware autorizado com `TEST_UNIT_READY`, `READ_CHIP_INFO`, `READ_FLASH_ID` e `READ_FLASH_INFO`;
- tratamento fail-closed para timeout, perda de sincronização e necessidade de reconexão;
- `READ_LBA` limitado localmente a no máximo 32 setores por transação;
- prova física e interface controlada para leitura fixa de exatamente 1 setor no LBA 0;
- proteção contra resultados obsoletos, leituras concorrentes e repetição após falha que exige reconexão;
- plano/parser para inspeção fixa de LBA 0–1 e detecção sanitizada de assinaturas MBR/GPT;
- integração interna da inspeção fixa de 2 setores no mesmo cliente somente leitura, ainda fora da UI padrão até validação física de `#35`.

Próximos gates:

1. concluir a matriz de hardware de `#18`, incluindo dispositivo não-Rockchip e attach/detach documentado;
2. validar em hardware autorizado a inspeção fixa de exatamente 2 setores de `#35`;
3. somente depois ampliar leituras físicas com faixas derivadas de metadata validada, política de destino e verificação de integridade;
4. manter backup físico genérico bloqueado até existirem retomada segura, limites testados e evidência de hardware para cada classe suportada.

## Fase 5 — Backup e restauração locais

**Estado: planejada após os gates read-only da Fase 4.**

Prioridades:

- backup físico streaming para destino SAF explicitamente selecionado;
- SHA-256 durante a leitura e verificação pós-operação;
- identificação inequívoca do alvo e da região lida;
- suporte incremental a partições, eMMC, NAND e SPI NAND conforme evidência física;
- restauração somente após validação de compatibilidade e confirmação explícita.

## Fase 6 — Gravação experimental

**Estado: bloqueada.**

A escrita só poderá ser introduzida após todos os seguintes gates:

- feature flag desativada por padrão;
- allowlist de dispositivos e operações explicitamente testados;
- confirmação textual específica para o alvo e a operação;
- revalidação do dispositivo, chip, memória e destino imediatamente antes da escrita;
- testes automatizados para validações, falhas parciais e recuperação;
- evidência de hardware autorizado para cada combinação suportada;
- abortamento seguro em desconexão, timeout ou condição de energia insuficiente;
- backup recomendado ou obrigatório conforme o risco;
- plano de recuperação documentado e testado;
- releitura e verificação de integridade após a gravação.

## Fase 7 — Recuperação avançada

**Estado: bloqueada até a maturidade das fases 4, 5 e 6.**

Loader/Maskrom, se forem técnica e legalmente viáveis, devem usar apenas loaders fornecidos ou autorizados pelo usuário e disponíveis localmente. O aplicativo não fará download automático desses artefatos.

Antes de qualquer envio ao hardware, o artefato deve ter:

- hash ou assinatura verificados contra metadados confiáveis disponíveis localmente;
- proveniência registrada;
- matriz explícita de compatibilidade entre loader, SoC, placa e modo de recuperação;
- validação prévia do artefato e do alvo selecionado;
- evidência de teste em hardware autorizado para a combinação suportada.

## Fase 8 — Plugins e programadores externos

**Estado: futuro.**

API de plugins estritamente locais, USB/serial e documentação para fabricantes continuam fora do corte `0.2.0-alpha01`. Plugins remotos ou baixados automaticamente não fazem parte da arquitetura offline.

## Release

A infraestrutura de release assinada, SBOM, provenance, checksums e publicação protegida está implementada. A ativação operacional permanece bloqueada pelos gates externos de `#20`.

Até esses gates serem concluídos, o artefato distribuível para testes é o APK `debug` gerado pelo CI. Ele é instalável, mas não representa uma release de produção assinada.
