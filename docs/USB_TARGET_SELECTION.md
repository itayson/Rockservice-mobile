# Seleção e monitoramento de alvo USB

## Seleção explícita

O aplicativo mantém no máximo um `transportId` selecionado por vez. A seleção só é aceita quando o descritor escolhido aparece exatamente uma vez na enumeração atual.

Após qualquer nova varredura, a seleção é reconciliada com a lista recém-enumerada. Se o alvo tiver sido removido, substituído ou se tornar ambíguo, a seleção é apagada automaticamente.

## Attach e detach

Enquanto a Activity está ativa, `AndroidUsbAttachmentMonitor` observa os broadcasts de conexão e desconexão USB do sistema.

O conteúdo do broadcast é tratado apenas como uma dica de mudança. Nenhum VID, PID, `transportId` ou outro campo recebido pelo broadcast é usado diretamente para autorizar uma operação. O evento apenas dispara uma nova enumeração completa pelo backend USB Host.

O receiver é registrado no `applicationContext` e removido de forma idempotente quando a Activity é destruída.

## Concorrência

Atualizações manuais e eventos de attach/detach compartilham um `Mutex`, impedindo duas varreduras simultâneas sobre o mesmo backend.

Cancelamento de coroutine continua sendo propagado e não é convertido em erro de interface.

## Limites

Esta etapa continua sem:

- `bulkTransfer()` ou `controlTransfer()`;
- claim de interface;
- comandos Loader ou Maskrom;
- leitura de armazenamento;
- escrita USB;
- root.

A seleção de um alvo apenas estabelece identidade de UI para futuras operações explicitamente autorizadas e revalidadas.
