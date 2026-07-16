# Segurança e operação de releases

## Estado

A cadeia de release é fail-closed: nenhum APK de release deve ser publicado se assinatura, testes, lint, verificação criptográfica, SBOM, checksum ou provenance falharem.

O workflow `.github/workflows/release.yml` separa duas responsabilidades:

1. `build-validate`: compila e valida o release candidate com permissões de leitura, attestations e OIDC;
2. `publish`: recebe apenas os artefatos já validados e é o único job com `contents: write`.

O job de publicação só é executado para tags compatíveis com `v*.*.*`. Execuções manuais podem validar um release candidate, mas não publicam uma GitHub Release.

## Secrets obrigatórios

Configure os seguintes GitHub Actions secrets antes de executar um release assinado:

- `RELEASE_KEYSTORE_BASE64`: conteúdo completo do keystore codificado em Base64;
- `RELEASE_KEYSTORE_PASSWORD`: senha do keystore;
- `RELEASE_KEY_ALIAS`: alias da chave de assinatura;
- `RELEASE_KEY_PASSWORD`: senha da chave.

O workflow falha antes do build caso qualquer um esteja ausente ou vazio.

O keystore é materializado apenas em `$RUNNER_TEMP`, com `umask 077` e permissão `0600`, e removido em uma etapa `if: always()` após o uso.

## Preparação do secret Base64

Em uma estação confiável, gere o valor Base64 sem alterar o arquivo original:

```bash
base64 -w 0 rockservice-release.keystore
```

Em sistemas cuja implementação de `base64` não suporta `-w`, use uma forma equivalente que produza uma única sequência Base64. Armazene apenas o resultado no secret `RELEASE_KEYSTORE_BASE64`.

Nunca versione:

- o keystore;
- senhas;
- arquivos `.jks` ou `.keystore` reais;
- dumps de secrets;
- chaves privadas.

## Build e verificação

Durante `build-validate`, o workflow:

1. verifica o hash conhecido do Gradle Wrapper;
2. exige os quatro secrets de assinatura;
3. materializa o keystore temporário;
4. executa testes, lint e `:app:assembleRelease`;
5. força `ROCKSERVICE_REQUIRE_RELEASE_SIGNING=true` para impedir build release sem a configuração completa;
6. verifica o APK final com `apksigner verify --verbose --print-certs`;
7. gera SHA-256 do APK;
8. gera SBOM CycloneDX do APK;
9. cria uma attestation de provenance para o APK;
10. envia APK, checksum e SBOM como um único release candidate temporário.

Nenhum passo desse job possui permissão para criar ou alterar uma GitHub Release.

## Publicação

O job `publish` só roda depois do sucesso integral de `build-validate` e apenas em evento de tag.

Antes da publicação ele:

1. baixa o release candidate validado;
2. revalida o checksum SHA-256;
3. confirma que a tag existe;
4. cria a GitHub Release com APK, checksum e SBOM.

A publicação deve ser combinada com regras de proteção de tags e, preferencialmente, um GitHub Environment protegido para produção. Essas regras são configuração administrativa do repositório e não devem ser substituídas por lógica dentro do aplicativo.

## Rotação da chave

A rotação deve ocorrer de forma controlada:

1. gerar ou provisionar a nova chave em ambiente confiável;
2. registrar internamente o fingerprint do certificado anterior e do novo;
3. atualizar os quatro secrets de release como uma única mudança operacional;
4. executar um release candidate manual e validar `apksigner verify --print-certs`;
5. somente depois criar a próxima tag de publicação;
6. revogar acessos à chave antiga e arquivá-la conforme a política de recuperação adotada.

A chave antiga não deve ser apagada sem considerar a estratégia de atualização dos APKs já distribuídos. A compatibilidade de assinatura do Android deve ser avaliada antes de qualquer troca definitiva de certificado.

## Resposta a comprometimento

Em caso de suspeita de vazamento:

1. interromper imediatamente novas tags/releases;
2. remover ou substituir os secrets afetados;
3. preservar logs de auditoria sem copiar os valores secretos;
4. identificar releases e commits potencialmente afetados;
5. gerar uma nova chave quando a política de assinatura permitir;
6. documentar a decisão de revogação/rotação e o impacto sobre atualizações futuras.

## Gates ainda externos ao código

A existência do workflow não conclui sozinha a preparação operacional de release. Antes de declarar o gate de distribuição concluído, ainda é necessário:

- configurar os secrets reais;
- configurar proteção administrativa das tags de release;
- executar com sucesso um release candidate assinado;
- validar uma publicação real ou um ensaio controlado conforme a política do projeto;
- registrar a evidência na issue de release.
