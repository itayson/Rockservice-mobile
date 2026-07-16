# Status da migração offline

A branch `refactor/offline-rockchip-only` redefine o RockService Mobile como aplicativo totalmente offline em runtime.

## Removido do produto

- tela e fluxo de validação ADB;
- `AdbProbeViewModel`;
- sessão e multiplexação ADB;
- codec e framing ADB;
- handshake `CNXN/AUTH`;
- diagnóstico remoto ADB;
- ADB Sync read-only;
- transporte USB específico de ADB;
- núcleo RSA de autenticação ADB;
- suíte de testes exclusiva desses componentes;
- módulo ADB planejado;
- documentação ativa do protocolo ADB.

## Política offline

O runtime deve operar somente com:

- USB/OTG;
- arquivos selecionados pelo usuário via Storage Access Framework;
- recursos estáticos empacotados no APK;
- armazenamento privado local quando estritamente necessário.

O CI executa `scripts/verify-offline-runtime.sh` e deve falhar caso sejam introduzidos no runtime:

- permissões Android de internet/rede;
- APIs de rede conhecidas;
- clientes HTTP comuns;
- WebView remoto;
- URLs remotas embutidas em código de runtime.

## Próximo foco

1. estabilizar esta migração até CI, CodeQL e Supply Chain ficarem verdes;
2. ampliar diagnóstico Rockchip somente leitura;
3. implementar backup físico local em streaming para destino SAF;
4. integrar backups ao Firmware Lab;
5. só depois introduzir restauração e escrita com gates físicos específicos.
