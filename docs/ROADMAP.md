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
- lifecycle USB e attach/detach.

Pendente:

- ViewModel/use cases para remover estado operacional da Activity;
- logs estruturados e exportação de relatório;
- banco local quando existir requisito de persistência.

## Fase 2 — Laboratório de firmware

**Estado: parcialmente implementada.**

Entregue:

- identificação de formatos por assinaturas;
- leitura em streaming;
- SHA-256;
- limites para arquivos e headers truncados.

Pendente:

- extração segura;
- sparse/raw;
- boot images;
- partições dinâmicas;
- empacotamento validado.

## Fase 3 — ADB e diagnóstico

**Estado: não iniciada.**

Planejado:

- ADB autorizado;
- coleta de logs;
- saúde do dispositivo;
- rede e armazenamento Android.

## Fase 4 — Rockchip somente leitura

**Estado: em andamento, bloqueada por validação de hardware para transporte real.**

Entregue:

- enumeração Android USB Host;
- permissão e revalidação de alvo;
- leitura de descritores USB brutos;
- inspeção passiva de interfaces/endpoints;
- probe conservador por VID/topologia;
- seleção de alvo e monitoramento attach/detach;
- codec CBW/CSW com allowlist de consultas de metadados;
- sessão abstrata serializada sem transporte físico;
- parsers defensivos de chip, flash e storage.

Próximos gates:

1. concluir a matriz de hardware de `#18`;
2. implementar o transporte real somente leitura de `#19`;
3. validar em hardware autorizado antes de expor consultas ativas na UI;
4. somente depois estudar leitura de dados/partições e backup.

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

Loader/Maskrom, quando tecnicamente e legalmente viáveis, devem usar loaders fornecidos ou autorizados pelo usuário e uma matriz explícita de compatibilidade.

## Fase 7 — Plugins e programadores externos

**Estado: futuro.**

API de plugins, USB/serial e documentação para fabricantes.

## Release

A publicação automática permanece desativada. Assinatura, SBOM, provenance e gates de distribuição estão rastreados em `#20`.
