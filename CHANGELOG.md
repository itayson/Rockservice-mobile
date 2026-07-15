# Changelog

## [Unreleased]

### Added

- Fundação Android modular e segura com CI, CodeQL, Gitleaks e supply-chain checks.
- Análise inicial de firmware com magic bytes, limites e SHA-256 em streaming.
- Parser estrutural Android Sparse com validação defensiva de headers, chunks, limites e contabilidade de blocos sem expansão do payload.
- Parser estrutural Android Boot Image v0-v4 com validação defensiva de headers, páginas, seções, offsets e truncamento sem extração de payload.
- Backend USB simulado com validação de alvo, timeout, cancelamento e lifecycle.
- Backend Android USB Host real somente leitura para enumeração e descritores brutos.
- Permissão USB controlada e revalidação do alvo antes da abertura.
- Inspeção passiva de interfaces e endpoints USB.
- Classificação conservadora de dispositivos Rockchip pelo vendor ID `0x2207`.
- Probe passivo de topologia bulk bidirecional sem inferir Loader ou Maskrom.
- Painel Compose de diagnóstico USB.
- Monitoramento attach/detach com re-enumeração completa e seleção de alvo único.
- Codec Rockchip de metadados com CBW/CSW e allowlist sem operações destrutivas.
- Núcleo de sessão Rockchip read-only abstrato, serializado e desconectado do hardware.
- Parsers defensivos para chip info, flash ID/info, storage e capability.
- Issues de gate para validação de hardware, transporte real read-only e release assinada.

### Security

- Publicação automática de releases desativada até assinatura, SBOM e gates bloqueantes.
- Subcódigos de erase/format excluídos estruturalmente da API read-only.
- Broadcasts USB tratados somente como gatilhos para nova enumeração, nunca como autorização de alvo.
- Transporte físico Rockchip permanece sem implementação até validação em hardware autorizado.
- Parser Android Sparse aplica limites explícitos e não expande payloads RAW durante a análise estrutural.
- Parser Android Boot aplica limites explícitos e valida o layout por streaming sem reter kernel, ramdisk ou outras seções em memória.
