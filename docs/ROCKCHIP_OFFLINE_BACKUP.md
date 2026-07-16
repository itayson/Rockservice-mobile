# Backup Rockchip offline

## Objetivo

O RockService Mobile executa backups Rockchip somente leitura sem ADB e sem rede. O fluxo usa apenas USB/OTG, uma sessão física explicitamente selecionada e um destino local escolhido pelo usuário via Storage Access Framework.

## Pipeline

1. enumerar novamente os dispositivos USB;
2. selecionar explicitamente o alvo autorizado;
3. executar a baseline de metadados Rockchip somente leitura;
4. bloquear o backup se qualquer consulta da baseline falhar ou exigir reconexão;
5. informar o LBA inicial e a quantidade de setores;
6. escolher explicitamente o arquivo de destino via SAF;
7. abrir uma única sessão Rockchip durante toda a operação;
8. ler a faixa em chunks de no máximo 32 setores;
9. calcular SHA-256 incremental antes de entregar cada buffer ao destino;
10. publicar progresso somente depois que o chunk foi aceito pelo sink;
11. fechar a sessão de forma não cancelável;
12. exibir contagem final de bytes e SHA-256.

## Limites atuais

O engine puro possui guardrails maiores para evolução futura, mas a primeira interface de produto permite no máximo 4.096 setores por operação, equivalentes a 2 MiB com setores lógicos de 512 bytes.

Esse limite não representa compatibilidade universal. A ampliação depende da matriz física e dos gates de validação de hardware.

## Arquivo parcial

O aplicativo só sinaliza que o destino pode conter dados parciais depois que o arquivo de destino foi efetivamente aberto. Se ocorrer timeout, desconexão, cancelamento ou erro de I/O depois desse ponto, o arquivo não deve ser tratado como backup válido.

Um backup é considerado concluído apenas quando:

- todos os setores solicitados foram lidos;
- todos os chunks foram gravados no destino;
- a operação chegou ao estado terminal de sucesso;
- o SHA-256 final foi calculado e apresentado.

## Operações ausentes

Este fluxo não implementa:

- escrita no dispositivo;
- erase;
- reset;
- download automático de loader;
- restauração;
- retomada de backup parcial;
- backup automático do armazenamento inteiro;
- acesso de rede.

## Próximos gates

1. validar o fluxo em hardware autorizado para diferentes SoCs e classes de armazenamento;
2. registrar throughput, estabilidade e comportamento em desconexão;
3. ampliar ranges apenas após evidência física suficiente;
4. gerar manifesto local de backup com metadados do alvo e hash;
5. implementar verificação independente do arquivo antes de qualquer futura restauração;
6. manter escrita física bloqueada até existir uma matriz explícita de compatibilidade e recuperação.
