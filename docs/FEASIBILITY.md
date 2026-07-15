# Análise de viabilidade

## Resumo

O Android pode ser uma estação portátil útil para análise de arquivos, coleta de dados, ADB por rede e USB Host autorizado. Ele **não substitui universalmente** um PC ou programador de bancada. O suporte efetivo depende do kernel do telefone, controlador USB, energia OTG, versão do Android, ABI, SELinux, permissões do fabricante e modo do alvo.

## Matriz

| Operação | Sem root | Root local | USB Host | Modo do alvo | Observações |
|---|---:|---:|---:|---|---|
| Hash, magic bytes, manifestos | Sim | Não | Não | Arquivo | SAF e streaming |
| Extração segura | Sim | Não | Não | Arquivo | Limitada por espaço, memória e formato |
| ADB por rede autorizado | Sim | Não | Não | ADB | Pareamento/autorização obrigatórios |
| ADB USB | Possível | Não | Sim | ADB | Requer implementação host e compatibilidade do telefone |
| Fastboot | Possível | Não | Sim | Bootloader/fastbootd | Nem todo telefone host mantém transporte estável |
| Enumerar VID/PID | Sim | Não | Sim | Qualquer USB enumerável | Permissão Android por dispositivo |
| Loader/Maskrom Rockchip | Parcial | Não | Sim | Loader/Maskrom | Protocolo, energia e endpoints precisam ser validados |
| Ler partições Rockchip | Experimental | Não | Sim | Loader/Maskrom | Loader legal e compatível pode ser necessário |
| Gravar partições Rockchip | Não no bootstrap | Não | Sim | Loader/Maskrom | Alto risco; exige backend e hardware testados |
| Ler blocos do próprio telefone | Não | Frequentemente | Não | Local | Bloqueado por sandbox/SELinux sem privilégio |
| Acessar `/dev/block`, MTD, dmesg | Não | Frequentemente | Não | Local | Depende de SELinux, kernel e política do root |
| Controlar NAND soldada diretamente | Não | Não | Não | Hardware externo | Requer programador/controlador compatível |

## Limitações Android

- **SELinux e sandbox:** um APK comum não acessa dispositivos de bloco, nós MTD, interfaces de depuração do kernel ou outros apps.
- **Scoped storage:** firmware deve ser acessado por SAF, descritores ou diretórios privados; caminhos arbitrários não são confiáveis.
- **USB:** a API oficial permite interfaces, endpoints e transferências após consentimento. Drivers desktop e ioctls específicos não migram automaticamente.
- **Energia:** muitos telefones não alimentam TV boxes com estabilidade. Hub OTG alimentado externamente pode ser necessário.
- **ARM64/Bionic:** binários Linux desktop podem depender de glibc, udev, libusb desktop, fork/exec, permissões ou ABI incompatível. Devem ser recompilados para Android/Bionic e auditados.
- **Background execution:** operações longas precisam de serviço em primeiro plano/WorkManager conforme aplicável; o sistema ainda pode encerrar processos sob pressão.
- **NDK:** código nativo não contorna permissões do Android. Também amplia a superfície para corrupção de memória.
- **Fabricantes:** alguns kernels removem USB Host, bulk transfer, isochronous support ou impõem limites não documentados.

## Modos do alvo

- **ADB:** Android inicializado com depuração autorizada.
- **Fastboot/fastbootd:** bootloader ou userspace fastboot disponível e desbloqueado conforme política do fabricante.
- **Loader Mode:** boot ROM/loader Rockchip aceita o protocolo suportado.
- **Maskrom Mode:** recuperação de baixo nível; frequentemente requer procedimento físico, loader compatível e conhecimento da placa.

## Riscos operacionais

Desconexão, suspensão, bateria baixa, cabo ruim, alimentação insuficiente ou imagem incompatível podem corromper bootloader, GPT ou partições críticas. O app deve impedir gravação se houver múltiplos alvos, bateria insuficiente, hash ausente, layout incompatível ou confirmação textual incorreta.

## Quando um PC ou programador externo continua necessário

- controlador USB do telefone incompatível;
- alvo exige driver/protocolo não implementável com a API host;
- necessidade de curto/test point, UART, SPI, eMMC socket ou acesso a OOB/ECC raw;
- recuperação de boot ROM não enumerável;
- firmware proprietário sem licença de redistribuição;
- alimentação e estabilidade superiores;
- depuração com analisador lógico, osciloscópio ou programador dedicado.

## Estado desta entrega

Somente análise local, política de segurança, detecção de capacidades e USB simulado. Nenhuma operação real em hardware foi declarada funcional.
