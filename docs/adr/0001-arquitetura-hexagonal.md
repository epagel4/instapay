# ADR-001 — Arquitetura Hexagonal (Ports & Adapters)

- **Status:** Aceito
- **Data:** 2026-11-01

## Contexto

O InstaPay movimenta dinheiro. As regras centrais — saldo derivado do ledger, partidas dobradas que somam zero, limites, estorno vinculado à transação original — precisam ser:

- **testáveis em milissegundos**, sem banco nem broker, inclusive com testes de propriedade (jqwik) gerando milhares de casos;
- **estáveis frente a mudanças de infraestrutura**: trocar lock pessimista por otimista, ou o transporte da saga, não pode reescrever regra de negócio;
- **auditáveis**: quem revisa o código deve encontrar todas as regras financeiras num só lugar, sem anotações de framework misturadas.

Numa arquitetura em camadas tradicional (controller → service → repository com entidades JPA), o modelo de domínio vira o modelo de persistência: setters para o Hibernate, lazy loading dentro de regras e invariantes espalhadas em services.

## Decisão

Arquitetura hexagonal em cada serviço, com **três módulos Gradle**:

| Módulo | Contém | Pode depender de |
|---|---|---|
| `*-domain` | Entidades, value objects (`Money`, `AccountId`, `PixKey`), invariantes, eventos de domínio | apenas JDK |
| `*-application` | Casos de uso (as classes públicas de serviço são as portas de entrada*) e portas de saída (`AccountRepository`, `LedgerRepository`, `TransactionRunner`…) | `domain` |
| `*-adapters` | Implementações: REST (web), persistência, Kafka/outbox (messaging), Redis, configuração Spring | `application`, `domain`, frameworks |

As regras valem em dois níveis:

1. **Compilação** — o grafo de módulos do Gradle: Spring não está no classpath de `domain` nem de `application`. Um import errado não compila.
2. **Testes ArchUnit** (`HexagonalArchitectureTest` em cada serviço) —
   - direção das dependências entre camadas;
   - núcleo (domain + application) sem Spring, Jakarta, Hibernate, Jackson, Kafka ou contratos de mensageria;
   - `adapters.web` não acessa `adapters.persistence`;
   - nenhum campo `double`/`float` no domínio;
   - núcleo não chama `Instant.now()`/`LocalDateTime.now()`/`LocalDate.now()` (tempo vem de `java.time.Clock` injetado).

O mapeamento entre domínio e persistência é explícito, feito no adapter.

\* Revisão na Fase 4: interfaces separadas para portas de entrada foram dispensadas — cada caso de uso tem uma única implementação e os adapters web são os únicos clientes, então a interface não agregaria. As portas de **saída** continuam interfaces (têm múltiplas implementações: `PessimisticAccountLocking`/`OptimisticAccountLocking`, fakes em teste).

Os dois serviços **não compartilham código de domínio** (bounded contexts distintos). Apenas `messaging-contracts` (records dos eventos) é compartilhado, e só adapters podem usá-lo.

## Alternativas consideradas

- **Camadas tradicionais (Spring MVC + JPA no domínio):** menos código, mas acopla invariantes ao ORM e dificulta testes de propriedade. Rejeitada.
- **Clean Architecture com 4+ camadas:** cerimônia extra sem ganho real para dois serviços deste tamanho. Rejeitada.
- **Hexagonal só por pacotes, num único módulo, garantida apenas por ArchUnit:** setup mais simples, mas a violação só aparece quando o teste roda. Rejeitada em favor dos módulos.

## Consequências

**Positivas**
- Domínio 100% testável com JUnit + jqwik, sem contexto Spring.
- Trocar a estratégia de locking (ADR-002) ou de mensageria afeta apenas adapters.
- Domínio isolado num módulo próprio permite medir e exigir cobertura ≥ 85% (JaCoCo) só nele.

**Negativas**
- Mais código de mapeamento (domínio ↔ persistência ↔ DTO REST).
- Wiring manual dos casos de uso via `@Configuration`.
- Curva de entrada maior para quem espera o layout padrão do Spring.

**Mitigação:** mappers manuais e pequenos (sem MapStruct por ora); convention plugins em `build-logic/` padronizam os módulos.
