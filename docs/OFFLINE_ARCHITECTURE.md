# RockService Mobile — Arquitetura Offline

## Decisão arquitetural

O RockService Mobile passa a ser um aplicativo totalmente offline em tempo de execução.

A aplicação não depende de internet, APIs remotas, telemetria, contas, serviços de nuvem ou ADB para executar suas funções principais.

## Escopo mantido

- Android USB Host / OTG para comunicação física direta com hardware compatível.
- Protocolos Rockchip Loader e MaskROM implementados localmente.
- Diagnóstico físico e identificação de dispositivos Rockchip.
- Leitura e backup autorizado de armazenamento acessível pelo protocolo suportado.
- Análise local de firmware.
- Android Sparse, Boot Image, super/liblp e formatos de filesystem suportados.
- Exportação e importação por Storage Access Framework.
- Logs técnicos locais e sanitizados.
- Hashes e validações de integridade calculados no dispositivo.

## Escopo removido

- ADB e ADB Sync.
- Autorização RSA ADB.
- Shell remoto por ADB.
- Diagnósticos coletados via comandos ADB.
- Qualquer funcionalidade que exija conexão com internet em runtime.
- Download automático de firmware, loaders, bancos de dados ou atualizações dentro do aplicativo.
- Telemetria e analytics remotos.

## Política de rede

O APK de produção não deve declarar `android.permission.INTERNET` nem `android.permission.ACCESS_NETWORK_STATE`.

Nenhum módulo de runtime deve depender de clientes HTTP, WebSocket, FTP, SSH ou bibliotecas equivalentes de rede.

GitHub, CodeRabbit e serviços de CI continuam sendo ferramentas de desenvolvimento e distribuição do projeto. Eles não fazem parte do runtime do aplicativo e não alteram a garantia de funcionamento offline do APK.

## Fonte de dados

Todo dado consumido pelo aplicativo deve vir de uma das seguintes origens:

1. dispositivo físico conectado diretamente por USB/OTG;
2. arquivo escolhido explicitamente pelo operador através do Android Storage Access Framework;
3. recurso estático empacotado e versionado dentro do próprio aplicativo.

## Princípio de segurança

A ausência de internet não elimina os controles de segurança.

Operações destrutivas devem continuar exigindo identificação do alvo, validação de compatibilidade, confirmação explícita, backup quando aplicável e verificação posterior de leitura.

## Plano de migração

1. Remover entradas de UI, Activity e estado de aplicação relacionados a ADB.
2. Remover implementação e testes do pacote `core-usb/.../adb`.
3. Remover chaves, armazenamento e lifecycle de identidade RSA ADB.
4. Auditar manifesto e dependências para garantir ausência de capacidades de rede.
5. Reorientar a UI para quatro áreas principais: Dispositivo Rockchip, Backup/Restauração, Laboratório de Firmware e Diagnóstico Local.
6. Adicionar gate automático de CI para impedir reintrodução de permissões ou dependências de rede no runtime.

Esta política deve ser tratada como requisito arquitetural do produto, não como preferência de interface.
