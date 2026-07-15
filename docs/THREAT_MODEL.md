# Modelo de ameaças

## Ativos
Firmware, backups, calibração, identificadores locais, dispositivo alvo, telefone host, chaves do Keystore e logs.

## Ameaças principais
- firmware malicioso explorando parsers;
- zip bomb, path traversal e integer overflow;
- imagem incompatível causando brick;
- troca de dispositivo entre validação e gravação;
- comando root construído por concatenação;
- atualização remota não assinada;
- vazamento de serial, MAC, certificados ou segredos em logs;
- loader proprietário incorporado sem licença;
- desconexão/energia insuficiente;
- dependência comprometida ou secret no CI.

## Controles
Streaming, limites de tamanho, magic bytes, hashes, confirmação textual, alvo único, allowlist, feature flags, logs redigidos, HTTPS, atualizações de dados assinadas, CodeQL, Dependabot, SBOM, revisão CodeRabbit e fuzzing.

## Fora de escopo
Bypass de DRM/FRP/MDM, exploração para root, clonagem fraudulenta de identificadores e extração de credenciais.
