# Android USB Host — backend somente leitura

## Escopo atual

A primeira implementação real de USB Host usa as APIs públicas do Android para:

- enumerar dispositivos conectados por `UsbManager`;
- preservar um identificador de transporte obtido da enumeração atual;
- coletar VID, PID, classe, subclasse e protocolo USB;
- verificar o estado de permissão sem abrir automaticamente o dispositivo;
- solicitar permissão ao usuário quando uma leitura de descritores for iniciada;
- revalidar o alvo antes e depois da concessão de permissão;
- abrir a conexão apenas depois da permissão e da revalidação;
- ler somente os descritores USB brutos expostos pelo Android;
- fechar a conexão imediatamente após a leitura;
- cancelar operações por timeout/cancelamento de coroutine;
- encerrar o receiver de permissão de forma idempotente no `close()`.

## Limites de segurança

Este backend não implementa:

- escrita USB;
- `bulkTransfer()` ou `controlTransfer()` para comandos proprietários;
- comandos Rockchip Loader/Maskrom;
- leitura ou escrita de NAND, SPI NAND, eMMC ou partições;
- claim forçado de interfaces USB;
- root;
- bypass de bootloader ou autenticação.

O método `read()` do backend Android, nesta fase, representa exclusivamente uma leitura limitada do snapshot de descritores USB brutos. Ele não deve ser interpretado como leitura de armazenamento do dispositivo.

## Identificação Rockchip

`RockchipUsbClassifier` reconhece de forma conservadora o vendor ID `0x2207`. A classificação não presume automaticamente que o dispositivo está em Loader ou Maskrom. A identificação de modo exigirá uma camada de protocolo Rockchip separada, somente leitura e validada em hardware autorizado.

## Revalidação do alvo

Um `UsbDeviceDescriptor` retornado por `listDevices()` contém um `transportId`. Antes da abertura, o backend consulta novamente a lista de dispositivos e compara VID, PID, classe, subclasse e protocolo quando disponíveis. Após uma eventual solicitação de permissão, a mesma validação é repetida imediatamente antes de abrir a conexão.

Isso reduz o risco de operar sobre um dispositivo diferente após desconexão e reconexão durante o fluxo de permissão.

## Próxima evolução permitida

A próxima camada pode implementar descoberta de interfaces e endpoints em modo somente leitura, desde que:

1. o alvo seja explicitamente allowlisted e revalidado;
2. nenhum comando de escrita seja enviado;
3. os timeouts sejam finitos;
4. desconexão e cancelamento encerrem a sessão;
5. existam fixtures e testes para respostas truncadas e malformadas;
6. qualquer protocolo Rockchip seja isolado do backend genérico USB Host.
