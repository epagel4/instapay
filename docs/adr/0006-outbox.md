# ADR-006 — Transactional outbox

- **Status:** Aceito
- **Data:** 2026-11-02

## Contexto

Ao iniciar uma transferência externa, o `payment-service` precisa **gravar no banco** (débito, saga) **e publicar no Kafka** (pedido de liquidação). Fazer os dois diretamente cria o problema de escrita dupla:

- publica e o commit falha → o settlement liquida algo que não existe;
- commita e a publicação falha (Kafka fora, crash) → dinheiro retido para sempre, saga sem pedido.

## Decisão

**Outbox transacional com relay por polling.**

1. O caso de uso chama a porta `SettlementGateway`; o adapter (`OutboxSettlementGateway`) **insere em `outbox_event` na mesma transação** do débito e da saga. Commit único: ou tudo, ou nada.
2. `OutboxRelay` (agendado a cada 200 ms) lê pendentes com `SELECT … FOR UPDATE SKIP LOCKED LIMIT 100`, envia ao Kafka (`acks=all`, produtor idempotente), espera as confirmações e marca `published_at` — tudo numa transação.
3. Falha no envio → rollback → linhas continuam pendentes → reenvio no próximo ciclo.

Garantia: **at-least-once**. Crash entre o envio e a marcação reenvia o lote; por isso os consumidores são idempotentes (ADR-005). Ordem por transferência garantida pela chave da mensagem (`transferId`) → mesma partição.

`SKIP LOCKED` permite várias instâncias do serviço rodarem o relay em paralelo sem enviar a mesma linha simultaneamente.

Observabilidade: `instapay_outbox_pending` (gauge) mostra o backlog — cresce se o Kafka estiver fora.

### settlement-service: sem outbox

O settlement **grava a decisão e depois publica** a resposta, confirmando o offset só após publicar. Se a publicação falhar, a mensagem é reentregue, a decisão gravada é encontrada e republicada. Funciona porque a resposta é **derivada do estado persistido** (não há efeito colateral adicional a proteger) — o outbox seria redundante ali.

## Alternativas consideradas

- **CDC (Debezium lendo o WAL)**: sem polling e com latência menor, mas adiciona Kafka Connect + Debezium à infraestrutura. Escolha natural em produção com volume alto; excesso para este escopo.
- **Transações Kafka (exactly-once)**: cobrem Kafka→Kafka, não a atomicidade com o Postgres.
- **Publicar após o commit (`@TransactionalEventListener(AFTER_COMMIT)`)**: perde a mensagem se o processo cair entre o commit e o envio.

## Consequências

- Latência adicional de até um ciclo de polling (~200 ms) antes da publicação — aceitável, já que a transferência externa é assíncrona.
- Carga de polling no banco, mitigada pelo índice parcial `WHERE published_at IS NULL`.
- `outbox_event` cresce: falta job de expurgo das linhas publicadas (anotado para produção).

## Adendo (Fase 8): contexto de trace

Publicar depois, em outra thread, quebraria o trace da requisição. Por isso a linha do outbox guarda o `traceparent` W3C da transação que a escreveu (`trace_parent`, migration V6), e o relay publica cada mensagem sob um span `PRODUCER` filho desse contexto, injetando o novo `traceparent` nos headers Kafka (`OutboxTracing`). A observação automática do `KafkaTemplate` fica desligada no payment-service, porque ela usaria como pai o poll do agendador. Custo: cerca de 55 bytes por linha.
