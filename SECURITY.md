# Segurança

Relate vulnerabilidades de forma privada aos mantenedores; não abra issue pública com exploit ou dados sensíveis.

## Garantias do bootstrap
- escrita USB real desativada;
- nenhum comando root;
- nenhum loader proprietário;
- cleartext traffic bloqueado;
- firmware tratado como entrada não confiável;
- confirmação textual testada para operações de alto risco.

## Política
Correções críticas têm prioridade. Releases devem ter SBOM, checks de dependência, CodeQL e artefatos assinados. Segredos nunca entram no repositório ou logs.
