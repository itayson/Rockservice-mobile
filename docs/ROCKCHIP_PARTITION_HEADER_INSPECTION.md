# Inspeção segura de cabeçalhos de partição Rockchip

## Objetivo

Este gate prepara o próximo passo entre a leitura física validada de um único setor no LBA 0 e qualquer futura leitura de tabelas de partição ou backup.

A operação permanece estritamente somente leitura e usa uma janela fixa, não configurável pelo operador:

- LBA inicial: `0`;
- quantidade: `2` setores;
- tamanho lógico assumido: `512` bytes por setor;
- total esperado: `1024` bytes.

O objetivo é validar leitura multi-setor mínima e reconhecer apenas assinaturas estruturais iniciais de MBR e GPT.

## Por que dois setores

O primeiro setor contém a assinatura final usada por MBR (`0x55AA`). O segundo setor é o local padrão do cabeçalho GPT primário, cuja assinatura começa com `EFI PART`.

A leitura desses dois setores permite diferenciar, de forma preliminar, estruturas de particionamento sem:

- percorrer entradas de partição;
- ler conteúdo de partições;
- aceitar LBA arbitrário;
- iniciar backup;
- persistir automaticamente bytes brutos.

## Fronteira de segurança

O plano é definido em `RockchipPartitionHeaderInspector` e não recebe offset ou quantidade informados externamente.

A inspeção aceita exatamente `1024` bytes. Qualquer resposta truncada ou maior que o esperado é rejeitada antes da análise.

O resultado sanitizado contém somente:

- LBA inicial fixo;
- quantidade fixa de setores;
- quantidade de bytes inspecionados;
- SHA-256 da janela;
- presença da assinatura MBR;
- presença da assinatura GPT.

O modelo não retém o conteúdo bruto.

## Integração com o transporte

`AndroidRockchipReadOnlyMetadataClient.inspectPartitionHeaders` integra o plano ao mesmo transporte somente leitura usado pelo baseline já validado.

A implementação:

1. compartilha o gate global que impede sessões Rockchip concorrentes;
2. abre e revalida o alvo pelo mesmo `RockchipReadOnlyTransportOpener`;
3. executa exatamente `readLba(startSector = 0, sectorCount = 2)`;
4. usa timeout finito;
5. exige resposta com exatamente `1024` bytes;
6. passa os bytes ao parser puro e retorna somente evidência sanitizada;
7. fecha a sessão dentro de prazo finito;
8. exige reconexão após timeout, falha de transporte, resposta inválida ou fechamento incompleto.

A operação permanece fora da UI padrão até existir evidência em hardware autorizado para a issue #35.

## Fora de escopo

Este gate não implementa:

- leitura das entradas GPT;
- interpretação completa de MBR/GPT;
- descoberta arbitrária de partições;
- leitura de filesystem;
- backup de partições;
- exportação automática do conteúdo bruto;
- escrita, erase, reset, loader, SDRAM ou eFuse.

## Critérios para o próximo gate

Antes de avançar para leitura controlada de estruturas de tabela de partições:

- validar a janela fixa de 2 setores em hardware Rockchip autorizado;
- registrar timeout, detach e reconexão;
- manter CI, CodeQL, Supply Chain e CodeRabbit verdes;
- concluir os itens pendentes da matriz física da issue #18;
- documentar o resultado na issue #35.

Backup de partições permanece explicitamente bloqueado até uma fase posterior.
