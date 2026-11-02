# ADR-003 — Idempotência de operações de escrita

- **Status:** Aceito
- **Data:** 2026-11-02

## Contexto

Clientes repetem requisições (timeout, retry automático, duplo clique). Em pagamentos, repetir não pode mover dinheiro duas vezes. Requisito: header `Idempotency-Key` obrigatório em toda escrita; repetição devolve a mesma resposta; armazenamento em Redis + Postgres.

O caso difícil não é a repetição sequencial, mas: (a) duas requisições com a mesma chave **ao mesmo tempo**; (b) crash **entre** o efeito de negócio e a gravação da resposta.

## Decisão

Um filtro HTTP (`IdempotencyFilter`) para todo `POST/PUT/PATCH/DELETE` em `/api/v1/**`:

1. Sem chave (ou > 100 caracteres) → `400 idempotency.key_missing`.
2. Calcula a impressão digital `SHA-256(método + caminho + corpo)`.
3. **Redis** (`idempotency:{key}`, TTL 24 h): se houver resposta, devolve-a (`Idempotent-Replayed: true`).
4. Abre **uma transação Postgres** e faz `INSERT INTO idempotency_record … ON CONFLICT DO NOTHING`:
   - inseriu → executa a requisição; o caso de uso roda em transação **aninhada (savepoint)**; grava a resposta na mesma transação; **COMMIT**;
   - não inseriu → já existe resposta commitada para a chave → replay.
5. Só **depois do COMMIT** a resposta é enviada ao cliente e copiada para o Redis.

Propriedades resultantes:

- **Atomicidade:** o registro da chave e o efeito de negócio commitam juntos. Não existe estado "em andamento" persistido nem chave órfã após crash — se o processo cair antes do COMMIT, nada aconteceu e o retry executa normalmente.
- **Concorrência:** uma segunda requisição com a mesma chave bloqueia no índice da chave primária até a primeira terminar; então lê a resposta commitada. Testado com 20 requisições simultâneas → 1 transferência, 20 respostas idênticas.
- **Mesma chave, corpo diferente** → `422 idempotency.key_reused`.
- **Respostas armazenadas:** 2xx e 4xx (inclusive regras de negócio — `422 insufficient_funds` é repetido mesmo após um depósito). **Não armazenadas** (transação desfeita, cliente pode repetir com a mesma chave): `409`, `429` e `5xx`.
- **Redis é otimização, não fonte de verdade:** indisponível ou sem a chave → Postgres responde e o cache é reaquecido. Erros de Redis viram log `WARN`, nunca falha da requisição.

## Alternativas consideradas

- **Estado "IN_PROGRESS" em tabela separada da transação de negócio** (padrão comum): exige timeout para chaves órfãs e uma segunda barreira de unicidade nas entidades para o caso de crash entre as etapas. Mais estados, mais código.
- **Só Redis (`SET NX`)**: rápido, mas não atômico com o Postgres — crash entre o commit e a gravação no Redis permite execução dupla.
- **Idempotência dentro de cada caso de uso**: repete lógica em todo endpoint e mistura HTTP (status/corpo) com aplicação.

## Consequências

- A transação dura a requisição inteira (inclusive serialização da resposta). Aceitável para operações curtas; o lock pessimista (ADR-002) fica retido até o fim dela.
- Requisições concorrentes com a mesma chave ocupam uma conexão cada enquanto esperam.
- Corpo limitado a 64 KB (precisa ser lido inteiro para a impressão digital).
- `idempotency_record` cresce sem limite: falta job de expiração (> 24 h) — anotado para a Fase 9/produção.
- A chave tem escopo global (não há autenticação ainda). Em produção, o escopo seria `(cliente, chave)`.
