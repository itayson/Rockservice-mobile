# Rockchip USB — probe passivo de transporte

## Objetivo

Esta camada inspeciona somente metadados já expostos pela pilha USB Host do Android. Ela não envia comandos ao dispositivo e não tenta identificar estados proprietários por tentativa e erro.

O probe combina:

- vendor ID e product ID da enumeração atual;
- identificador de transporte revalidado;
- interfaces USB declaradas;
- classe, subclasse e protocolo de cada interface;
- endpoints declarados, direção e tipo de transferência.

## Níveis de resultado

`NOT_ROCKCHIP` significa que o vendor ID não é `0x2207`.

`ROCKCHIP_VENDOR_ONLY` significa que o vendor ID pertence à Rockchip, mas a topologia observada não apresenta um par de endpoints bulk IN/OUT.

`ROCKCHIP_BULK_TRANSPORT_CANDIDATE` significa apenas que um dispositivo com vendor ID Rockchip também declara pelo menos um endpoint bulk IN e um endpoint bulk OUT. Esse resultado é um indício de topologia de transporte e não identifica Loader, Maskrom, bootloader, SoC ou capacidade de gravação.

## Garantias de segurança

O probe passivo:

- não chama `bulkTransfer()`;
- não chama `controlTransfer()`;
- não reivindica interfaces;
- não abre uma sessão de protocolo Rockchip;
- não lê NAND, SPI NAND, eMMC ou partições;
- não escreve bytes no dispositivo;
- não requer root;
- rejeita topologia pertencente a outro `transportId`.

## Próxima etapa

Uma futura camada de protocolo somente leitura deve ser isolada deste probe e exigir:

1. allowlist explícita de VID/PID/topologia;
2. seleção inequívoca de uma interface e de endpoints;
3. permissão Android válida;
4. revalidação do alvo imediatamente antes de abrir a sessão;
5. timeouts finitos e cancelamento cooperativo;
6. fixtures de respostas válidas, truncadas e malformadas;
7. ausência completa de comandos de escrita até existir um gate de segurança separado e validação em hardware autorizado.
