# Segurança

## Relato de vulnerabilidades

Não abra issue pública com exploit funcional, segredo, dado pessoal ou evidência sensível.

Use preferencialmente o fluxo privado **Security → Report a vulnerability** do GitHub quando ele estiver habilitado no repositório. Se esse recurso não estiver disponível, contate o mantenedor por um canal privado publicado no perfil `@itayson` antes de divulgar detalhes técnicos.

Inclua, quando possível, versão afetada, impacto, pré-condições, passos mínimos de reprodução e uma proposta de mitigação. Remova tokens, seriais, chaves, certificados e outros identificadores sensíveis dos anexos e logs.

## Garantias do bootstrap

- escrita USB real desativada;
- nenhum comando root;
- nenhum loader proprietário;
- cleartext traffic bloqueado;
- firmware tratado como entrada não confiável;
- confirmação textual testada para operações de alto risco;
- publicação automática de releases desativada até a implantação dos gates de distribuição.

## Política

Correções críticas têm prioridade. Uma futura publicação de release deve exigir SBOM, checks bloqueantes de dependências e segredos, CodeQL, artefato assinado com verificação independente e evidência de validação em hardware autorizado. Segredos nunca entram no repositório ou logs.
