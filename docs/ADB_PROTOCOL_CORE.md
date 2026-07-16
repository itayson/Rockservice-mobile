# Núcleo defensivo do protocolo ADB

## Objetivo

O primeiro gate da Fase 3 implementa somente o framing do protocolo de transporte ADB. Nenhum dispositivo é aberto e nenhum comando é executado neste componente.

## Mensagens suportadas

O codec reconhece os comandos de transporte:

- `SYNC`;
- `CNXN`;
- `AUTH`;
- `OPEN`;
- `OKAY`;
- `CLSE`;
- `WRTE`.

O header possui 24 bytes em little-endian e contém comando, dois argumentos uint32, tamanho do payload, checksum e magic do comando.

## Validações

Antes de aceitar uma mensagem:

- o header deve ter exatamente 24 bytes;
- o comando deve pertencer à allowlist local;
- o magic deve corresponder a `command xor 0xffffffff`;
- o tamanho declarado deve respeitar o limite local de payload;
- o payload recebido deve ter exatamente o tamanho declarado;
- o checksum aditivo pode ser exigido ou explicitamente dispensado pelo transporte após negociação de uma versão moderna.

O checksum legado soma os bytes do payload como valores unsigned.

## Helpers

O núcleo fornece builders para:

- `CNXN` com banner terminado em NUL;
- `OPEN` para um serviço explicitamente escolhido;
- `AUTH` com assinatura;
- `AUTH` com registro de chave pública terminado em NUL.

Nenhum serviço shell é aberto automaticamente.

## Próximos gates

1. armazenamento e geração de identidade RSA ADB no Android Keystore ou armazenamento privado do app;
2. codificação do registro de chave pública compatível com ADB;
3. transporte USB Host limitado a interfaces ADB (`FF/42/01`);
4. handshake `CNXN/AUTH` com autorização explícita no dispositivo remoto;
5. serviços diagnósticos allowlisted antes de qualquer shell arbitrário.

## Fora de escopo

Este componente não:

- solicita permissão USB;
- reivindica interfaces;
- assina tokens AUTH;
- abre shell;
- executa comandos;
- faz pareamento wireless;
- grava em hardware.
