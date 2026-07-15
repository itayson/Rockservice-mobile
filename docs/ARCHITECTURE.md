# Arquitetura

## Estado atual do bootstrap

O bootstrap atual ainda não implementa a cadeia completa de Clean Architecture no entry point. `MainActivity` instancia `CapabilityDetector` diretamente na composição para manter a primeira entrega mínima e verificável.

Esse acoplamento é temporário e não deve ser usado como precedente para operações de hardware, firmware, shell, root ou escrita. Antes da introdução de backends reais, a composição deve migrar para ViewModel/use cases e portas explícitas.

## Arquitetura alvo

Fluxo planejado: UI → ViewModel/use case → política → porta → adaptador.

```mermaid
flowchart LR
  UI[Compose UI] --> VM[ViewModel]
  VM --> UC[Casos de uso]
  UC --> POL[Políticas de segurança]
  UC --> PORT[Interfaces/ports]
  PORT --> USB[Android USB Host]
  PORT --> SIM[USB simulado]
  PORT --> FW[Parsers de firmware]
  PORT --> NDK[Adaptadores NDK]
```

## Regras

1. UI não executa shell, root, USB ou escrita.
2. Operações críticas exigem alvo único, validação e confirmação textual.
3. Parsers tratam todo arquivo como não confiável.
4. NDK fica atrás de interfaces Kotlin.
5. Escrita real permanece desativada por build flag e por política até os gates de segurança serem implementados.
6. Compatibilidade precisa de evidência e data de teste.
7. Backends reais devem possuir lifecycle explícito, timeout, cancelamento cooperativo e encerramento idempotente.

## Fluxo USB

```mermaid
sequenceDiagram
  participant U as Usuário
  participant A as App
  participant O as Android USB API
  participant D as Dispositivo
  A->>O: enumerar
  O-->>A: VID/PID
  A->>U: solicitar permissão
  U-->>O: consentimento
  A->>D: consulta somente leitura
  D-->>A: resposta
  A->>A: validar e registrar
```

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
