# ADR-002 — Estratégia de locking para débitos concorrentes

- **Status:** Aceito
- **Data:** 2026-11-02

## Contexto

O saldo é derivado do ledger (`SUM(amount_cents)`), não armazenado. Uma transferência lê o saldo, decide se há fundos e grava os lançamentos. Sem controle, duas transferências simultâneas da mesma conta leem o mesmo saldo e ambas passam — saldo negativo.

Requisito: **nunca** gerar saldo negativo nem divergência no ledger, com p99 < 200 ms a 500 req/s.

## Opções implementadas

Ambas atrás da porta `AccountLocking` (`loadForDebit` no início da transação, `confirmDebit` no fim), selecionáveis por `instapay.locking.strategy`:

| | Pessimista | Otimista |
|---|---|---|
| Mecanismo | `SELECT … FROM account WHERE id = ? FOR UPDATE` na conta de origem | `UPDATE account SET version = version + 1 WHERE id = ? AND version = ?` no fim; 0 linhas → conflito → retry (até 10x, backoff com jitter) |
| Equivalente JPA | `@Lock(PESSIMISTIC_WRITE)` | `@Version` (mesmo SQL) |
| Créditos | não travam (só aumentam o saldo) | idem |

Por que o otimista é correto em READ COMMITTED: o `UPDATE` concorrente espera o lock de linha do primeiro committer e, após o commit dele, reavalia `version = ?` contra a versão nova — falha. A transação perdedora é desfeita inteira (incluindo lançamentos) e repetida.

Só a conta de origem é travada, então não há deadlock por ordem de aquisição.

## Medições

`TransferConcurrencyTest`: 100 requisições HTTP simultâneas (cada uma com sua Idempotency-Key) debitando R$ 10,00 da mesma conta. Testcontainers (Postgres 16) em MacBook, pool Hikari padrão (10). 4 execuções:

| Cenário | Estratégia | Sucesso (201) | Sem fundos (422) | Esgotou retries (409) | Conflitos de versão | Tempo total |
|---|---|---|---|---|---|---|
| R$ 500 (só 50 cabem) | Pessimista | **50** (sempre) | 50 | 0 | 0 | 281–336 ms |
| | Otimista | 50 | 43–47 | 3–7 | 175–216 | 383–691 ms |
| R$ 1000 (todas cabem) | Pessimista | **100** (sempre) | 0 | 0 | 0 | 478–623 ms |
| | Otimista | 84–93 | 0 | **7–16** | 279–367 | 706–1142 ms |

Invariantes verificadas em **todas** as execuções, nas duas estratégias: saldo final = inicial − 10 × sucessos, nunca negativo; nº de transferências = nº de débitos no ledger = nº de 201; soma global dos lançamentos = 0.

## Decisão

**Lock pessimista como padrão.** O otimista fica implementado e testado para comparação.

- Contenção na mesma conta é o caso normal de um sistema de pagamentos (conta de lojista recebendo e pagando, folha de pagamento, conta "quente"). Nesse cenário o otimista desperdiça trabalho (até ~3,7 tentativas por sucesso), é mais lento e **rejeita transferências legítimas** (7–16% de 409 com fundos sobrando).
- O pessimista é determinístico: mesma entrada, mesmo resultado. Isso simplifica testes, suporte e reconciliação.
- O custo do pessimista — débitos da mesma conta serializados — é inerente ao problema: eles precisam ser serializados de qualquer forma para checar saldo. O lock fica restrito a uma linha e à duração de uma transação curta.

## Consequências

- Throughput por conta é limitado pela duração da transação de débito; mantê-la curta é obrigatório (sem I/O externo dentro dela — a saga da Fase 6 publica via outbox, não chama Kafka na transação).
- Conexões ficam ocupadas esperando o lock: com contenção extrema numa única conta, o pool pode ser o gargalo. Medir na Fase 9.
- O otimista seria preferível com contenção rara e leituras dominantes; trocar é uma linha de configuração.
- Retry com conflito esgotado retorna `409 concurrency.conflict`, que **não** é armazenado pela idempotência (o cliente pode repetir com a mesma chave).
