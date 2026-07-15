# Arquitetura

## Estado atual

A interface principal já não mantém o estado operacional do diagnóstico USB. O fluxo atual separa:

- `MainActivity`: composição da UI e lifecycle dos adaptadores Android ligados à Activity;
- `UsbDiagnosticsViewModel`: cancelamento do refresh anterior e exposição do estado para a UI;
- `core-usb/UsbDiagnosticsCoordinator`: transições determinísticas de estado e reconciliação da seleção;
- `core-usb/AndroidUsbDiagnosticsScanner`: enumeração e inspeção passiva de dispositivos;
- `UsbDiagnosticsUiState.kt`: projeção dos snapshots do core para textos/modelos de apresentação;
- `AndroidUsbHostBackend`: adaptação concreta da API USB Host do Android.

`CapabilityDetector` ainda é instanciado diretamente pela composição porque sua leitura é local, síncrona e sem estado operacional persistente. Operações futuras de firmware, shell, root ou escrita não devem seguir esse atalho.

## Arquitetura alvo

Fluxo: UI → ViewModel → coordenador → scanner/caso de uso → política/porta/adaptador.

```mermaid
flowchart LR
  A[MainActivity / lifecycle] --> VM[UsbDiagnosticsViewModel]
  A --> SCAN[core-usb AndroidUsbDiagnosticsScanner]
  UI[Compose UI] --> VM
  VM --> COORD[core-usb UsbDiagnosticsCoordinator]
  COORD --> SCAN
  COORD --> POL[Políticas de seleção]
  SCAN --> USB[AndroidUsbHostBackend]
  SCAN --> PROBE[Probe passivo Rockchip]
  USB --> API[Android USB API]
  COORD --> VM
  VM --> UI
  UI --> MAP[Mapeamento de apresentação toUiModel]
```

O estado e as regras passivas reutilizáveis ficam no `core-usb`. O módulo `app` traduz os snapshots para rótulos de interface e mantém apenas a coordenação de lifecycle da tela.

Para funcionalidades novas, a direção de dependência deve continuar favorecendo casos de uso e modelos testáveis. Adaptadores Android, NDK, rede ou root não devem ser invocados diretamente por composables.

## Regras

1. UI não executa shell, root, USB ou escrita diretamente.
2. Estado operacional de fluxos assíncronos deve permanecer fora de `Activity`/composables.
3. Regras reutilizáveis de enumeração e seleção USB pertencem ao `core-usb`, não ao módulo de apresentação.
4. Operações críticas exigem alvo único, validação e confirmação textual.
5. Parsers tratam todo arquivo como não confiável.
6. NDK fica atrás de interfaces Kotlin.
7. Escrita real permanece desativada por build flag e por política até os gates de segurança serem implementados.
8. Compatibilidade precisa de evidência e data de teste.
9. Backends reais devem possuir lifecycle explícito, timeout, cancelamento cooperativo e encerramento idempotente.
10. Eventos de attach/detach nunca autorizam um alvo; apenas solicitam nova enumeração.

## Fluxo USB passivo atual

```mermaid
sequenceDiagram
  participant O as Android USB API
  participant A as MainActivity
  participant VM as ViewModel
  participant C as Core Coordinator
  participant S as Core Scanner
  participant B as USB Backend
  participant UI as Compose UI

  O-->>A: attach/detach
  A->>VM: refresh(scanner)
  VM->>VM: cancelar refresh anterior
  VM->>C: refresh(scanner)
  C->>S: scan()
  S->>B: enumerar e inspecionar topologia
  B-->>S: descritores
  S-->>C: snapshot de diagnóstico
  C->>C: reconciliar alvo selecionado
  C-->>VM: StateFlow
  VM-->>UI: estado observado
```

O backend e o monitor Android permanecem ligados ao lifecycle da `Activity` porque são recriados após mudança de configuração. O `ViewModel` não retém esses recursos; cada nova `Activity` fornece um scanner novo e cancela qualquer refresh iniciado pelo host anterior antes de fechar o backend.

## Fluxo Rockchip somente leitura futuro

O codec, a sessão abstrata e os parsers já existem, mas o transporte físico continua bloqueado por validação de hardware.

```mermaid
flowchart LR
  UI[UI] --> VM[ViewModel]
  VM --> UC[Consulta de metadados]
  UC --> POL[Revalidar alvo/topologia/permissão]
  POL --> SESSION[Sessão read-only]
  SESSION --> TRANSPORT[Transporte Android validado]
  TRANSPORT --> USB[USB físico]
```

A implementação de `TRANSPORT` está rastreada em `#19` e depende da matriz física de `#18`.

## Fluxo de gravação futuro

```mermaid
flowchart TD
  D[Detectar alvo único] --> C[Identificar chip e memória]
  C --> V[Validar imagem, tamanho, layout e hash]
  V --> B[Recomendar/confirmar backup]
  B --> P[Checar bateria, energia e suspensão]
  P --> T[Exigir frase textual]
  T --> RV[Revalidar alvo imediatamente antes da escrita]
  RV --> W[Gravar por backend aprovado]
  W --> R[Reler e verificar hash]
  R --> REP[Gerar relatório]
```

## Sequência de backup

```mermaid
sequenceDiagram
  participant U as Usuário
  participant UC as BackupUseCase
  participant B as Backend
  participant S as SAF
  UC->>B: consultar geometria e partições
  B-->>UC: metadados
  UC->>S: criar destino
  loop blocos
    UC->>B: ler bloco
    B-->>UC: bytes
    UC->>S: gravar e atualizar hash
  end
  UC->>S: gravar manifesto e verificação
  UC-->>U: relatório final
```
