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

**Estado: em andamento.**

Entregue:

- app Compose;
- detecção de capacidades;
- painel de diagnóstico USB;
- seleção explícita de alvo;
- lifecycle USB e attach/detach;
- ViewModel e coordenador testável para remover o estado operacional USB da `Activity`;
- relatório sanitizado de validação de hardware com exportação controlada;
- invalidação de jobs e resultados obsoletos ao trocar o alvo;
- log técnico estruturado, sanitizado e limitado em memória;
- tela de consulta, limpeza e exportação manual em JSONL sem permissão ampla de armazenamento.

Pendente:

- integração do log estruturado com fluxos adicionais de firmware, ADB e operações críticas;
- casos de uso adicionais para futuros fluxos críticos;
- banco local somente quando existir requisito explícito de persistência.

## Fase 2 — Laboratório de firmware

**Estado: parcialmente implementada.**

Entregue:

- identificação de formatos por assinaturas;
- leitura em streaming;
- SHA-256;
- limites para arquivos e headers truncados;
- parser estrutural Android Sparse com validação de headers, chunks, limites e contabilidade de blocos;
- parser estrutural Android Boot Image v0-v4 com validação de layout, alinhamentos, offsets e truncamento;
- parser raw de metadata de partições dinâmicas Android `super`/liblp com validação de geometria, checksums, tabelas e referências cruzadas.

Pendente:

- expansão e extração segura de Android Sparse;
- interpretação adicional e extração controlada de payloads de boot images;
- tradução segura de `super.img` sparse para o parser raw;
- mapeamento e extração controlada de partições lógicas;
- análise estrutural aprofundada de imagens raw/filesystems;
- empacotamento validado.

## Fase 3 — ADB e diagnóstico

**Estado: não iniciada.**

Planejado:

- ADB autorizado;
- coleta de logs;
- saúde do dispositivo;
- rede e armazenamento Android.

## Fase 4 — Rockchip somente leitura

**Estado: em andamento. Transporte real, baseline ativo e primeira leitura LBA limitada já foram validados em hardware autorizado; a expansão continua protegida por gates.**

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
- plano preparatório e parser puro para inspeção fixa de LBA 0–1 e detecção sanitizada de assinaturas MBR/GPT;
- integração da inspeção fixa de 2 setores no mesmo cliente de transporte somente leitura, compartilhando o gate global de sessão e permanecendo fora da UI padrão até validação física de `#35`.

Próximos gates:

1. concluir a matriz de hardware de `#18`, incluindo dispositivo não-Rockchip e evidência explícita de attach/detach;
2. concluir formalmente o checklist de `#19`, cujo transporte e baseline ativo já possuem evidência em hardware autorizado;
3. validar em hardware autorizado a inspeção fixa de exatamente 2 setores (LBA 0–1) de `#35`, sem LBA configurável e sem persistência automática de bytes brutos;
4. somente após `#35`, projetar leitura estritamente limitada das estruturas necessárias para mapear tabelas de partição, com limites derivados e validados;
5. manter backup de partições bloqueado até existir mapeamento confiável, validação de tamanho/faixa, política de destino, retomada segura e verificação de integridade.

## Fase 5 — Gravação experimental

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

## Fase 6 — Recuperação avançada

**Estado: bloqueada até a maturidade das fases 4 e 5.**

Loader/Maskrom, se forem técnica e legalmente viáveis, devem usar apenas loaders fornecidos ou autorizados pelo usuário. Antes de qualquer envio ao hardware, o artefato deve ter:

- hash ou assinatura verificados contra uma origem confiável;
- proveniência registrada;
- matriz explícita de compatibilidade entre loader, SoC, placa e modo de recuperação;
- validação prévia do artefato e do alvo selecionado;
- evidência de teste em hardware autorizado para a combinação suportada.

## Fase 7 — Plugins e programadores externos

**Estado: futuro.**

API de plugins, USB/serial e documentação para fabricantes.

## Release

A publicação automática permanece desativada. Assinatura, SBOM, provenance e gates de distribuição estão rastreados em `#20`.
