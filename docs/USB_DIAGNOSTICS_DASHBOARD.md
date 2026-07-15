# Painel de diagnóstico USB

## Comportamento

O painel inicial do RockService Mobile passa a executar uma varredura USB passiva ao abrir o aplicativo e permite atualização manual.

Para cada dispositivo enumerado, a interface apresenta:

- nome de produto ou fabricante quando disponível;
- VID e PID;
- estado atual da permissão USB;
- quantidade de interfaces e endpoints declarados;
- resultado do probe passivo Rockchip.

## Limites

A varredura do painel não solicita permissão USB e não envia transferências ao dispositivo. Ela usa apenas a enumeração e a topologia declarada pelo Android USB Host.

A classificação `Rockchip com transporte bulk bidirecional candidato` não significa Loader ou Maskrom e não autoriza comandos de escrita.

## Lifecycle

O backend USB Host é mantido durante a vida da `MainActivity` para evitar múltiplos receivers de permissão. No encerramento da Activity, o backend é fechado e o receiver é removido.

## Próximas evoluções

- mover o estado do painel para uma camada ViewModel/use case;
- reagir a eventos de attach/detach sem depender apenas de atualização manual;
- permitir seleção explícita de um único alvo;
- adicionar uma sessão de protocolo somente leitura atrás de allowlist e validação de hardware.
