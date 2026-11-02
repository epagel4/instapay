# Performance — transferência interna

Meta da especificação: **p99 < 200 ms a 500 req/s** em `POST /api/v1/transfers` (transferência interna).

**Resultado, em uma linha:** a meta foi atingida (p99 de 153 ms, 0 erros), mas não de forma reproduzível neste notebook compartilhado. As otimizações melhoram p95 e p99 em todas as comparações pareadas. Os valores absolutos variam de 2 a 6 vezes entre rodadas idênticas, conforme a carga de outros processos.

## Como rodar

```bash
docker compose up -d --build
./scripts/perf.sh <rótulo> load 500 120    # rampa de 30 s + 120 s a 500 req/s; asserts p99 < 200 ms e < 1% de erros
./scripts/perf.sh <rótulo> spike 500       # 100 req/s → 1000 req/s (2× a meta) por 30 s → 100 req/s
```

Simulação: [`InternalTransferSimulation`](../../performance-tests/src/gatling/java/com/instapay/perf/InternalTransferSimulation.java).
- **Modelo aberto:** chegadas por segundo, como tráfego real. Clientes não esperam uns pelos outros, então a fila aparece na latência.
- **Massa:** 2.000 contas criadas e com saldo antes da medição. Cada requisição usa um par aleatório de contas, um valor em centavos e uma `Idempotency-Key` nova.
- **Saída:** relatórios HTML do Gatling em [`reports/`](reports/), e um `summary.txt` por rodada.

## Ambiente, e por que os números são ruidosos

- **Máquina:** MacBook com 8 núcleos e 16 GB. Docker Desktop com 8 vCPU e 7,7 GB. Toda a stack (Postgres, Redis, Kafka, os dois serviços, Prometheus, Grafana, Jaeger) roda na mesma VM, e o Gatling roda no host.
- **Concorrência na máquina:** durante as medições, outros projetos competiram por CPU: um Postgres de outro projeto a 150–440% dentro da mesma VM, um daemon Gradle a 340% e, por um período, `simctl diagnose` do simulador iOS. A carga média variou de **4 a 472**. Com a carga em ~470 o Docker Desktop reiniciou, e as rodadas daquele período foram descartadas.
- **Mitigação:**
  - comparações sempre **intercaladas** (antes/depois/antes/depois), no mesmo intervalo de tempo;
  - serviço reiniciado antes de cada rodada (JVM fria igual para todos; os 30 s de rampa servem de aquecimento);
  - a carga média registrada junto de cada resultado.

## Diagnóstico do baseline

Baseline (pool Hikari padrão de 10 conexões), a 500 req/s: **p50 27 ms, p95 1.342 ms, p99 4.166 ms**, sem erros. As métricas do Prometheus na mesma janela mostraram onde estava o tempo:

| Métrica | Valor |
|---|---|
| Tempo médio no servidor (`POST /transfers`) | 133 ms |
| **Espera por conexão do pool** (média) | **112 ms** |
| Tempo com a conexão presa (média) | 13,9 ms |
| Threads esperando conexão (máx.) | 190 |
| Conexões ativas | 10 de 10 |

O gargalo é o pool de conexões, não o banco. O `pg_stat_statements` mostrou que nenhuma query passa de 0,3 ms de execução no Postgres. Uma transferência faz cerca de 15 idas e voltas numa única transação:
- idempotência e transação: claim da chave, savepoint e release, resposta guardada;
- conta: lock do devedor, leitura do credor, saldo;
- limites: limites da conta e soma do dia;
- escritas: transferência, lançamento contábil, 2 postings e auditoria.

A conexão fica presa durante a requisição inteira, de propósito: a chave de idempotência, o efeito de negócio e a resposta guardada fazem commit juntos ([ADR-003](../adr/0003-idempotencia.md)). Com 10 conexões e cerca de 14 ms por transação, o limite teórico é de ~700 tx/s. Na prática, sob contenção de CPU, o tempo por transação cresce, o pool satura e a espera vira fila.

## Otimizações

| # | Mudança | Por quê |
|---|---|---|
| 1 | **Pool Hikari 10 → 20** (`application.yml`; no Compose dá para sobrescrever com `PAYMENT_DB_POOL`) | Remove a fila no pool. Medido: 40 é pior que 20 (cada conexão passa a ficar presa 7,8 ms em vez de 4,0, por contenção no Postgres) |
| 2 | **Cache Redis da idempotência gravado depois de enviar a resposta** (`IdempotencyFilter`: `flushBuffer()` antes de `store.cache`) | São 2 comandos Redis, com timeout de 200 ms, que estavam no caminho da latência. Durante o baseline o log mostrou `Redis command timed out`. O cache é best effort: perder a escrita só significa que um replay vai consultar o Postgres |

**Testadas e não adotadas:**
- **Pool de 40:** pior que 20.
- **Amostragem de traces em 10%:** o efeito ficou abaixo do ruído (veja abaixo).

**Consideradas e não feitas:** juntar queries (saldo junto com o lock, limites junto com a soma). Economizariam 2 a 3 das ~15 idas e voltas, ao custo de mudar portas da aplicação. Com o pool dimensionado, a conexão fica presa ~4 ms quando a máquina está calma, então o ganho não justificava a mudança agora.

## Resultados

### Tamanho do pool (mesma imagem; rodadas seguidas; carga 14–20; 60 s a 500 req/s)

| Pool | p50 | p95 | p99 | máx. | Espera por conexão (média) | Conexão presa (média) |
|---|---|---|---|---|---|---|
| 10 | 4 | 120 | 520 | 1.380 | 6,5 ms | 4,3 ms |
| **20** | 4 | **25** | **75** | 163 | 0,1 ms | 4,0 ms |
| 40 | 5 | 114 | 517 | 1.079 | 2,3 ms | 7,8 ms |

Relatórios: [pool10](reports/pool10-load/index.html) · [pool20](reports/pool20-load/index.html) · [pool40](reports/pool40-load/index.html)

### Antes × depois (intercalado; 120 s a 500 req/s; 67.650 requisições por rodada)

| Rodada | Versão | Carga média | p50 | p95 | p99 | Erros |
|---|---|---|---|---|---|---|
| 1 | antes | 26 → 113* | 15.781 | 35.825 | 36.810 | 17,4% |
| 1 | depois | 27 → 95* | 11.273 | 26.403 | 26.497 | 6,3% |
| 2 | antes | 19 → 79* | 1.079 | 14.018 | 14.144 | 0 |
| 2 | depois | 11 → 66* | 7 | 625 | 1.355 | 0 |
| 3 | depois | 6 | 3 | 16 | **153** ✅ | 0 |
| 3 | antes | 7 | 3 | 37 | 488 | 0 |
| 3 | depois | 7 | 3 | 20 | 305 | 0 |

\* Carga de 1 min → 15 min: as rodadas 1 e 2 aconteceram logo depois do pico de 472, com a máquina ainda se recuperando. Só a rodada 3 representa a máquina razoavelmente calma.

Relatórios da rodada 3: [antes](reports/before-3-load/index.html) · [depois (153 ms)](reports/after-3-load/index.html) · [depois (305 ms)](reports/after-4-load/index.html). Das rodadas 1 e 2 ficaram só os `summary.txt`.

### Spike: 100 → 1.000 → 100 req/s (carga ~5–7)

| Versão | p50 | p95 | p99 | máx. | Erros |
|---|---|---|---|---|---|
| antes | 4 | 166 | 446 | 824 | 0 |
| depois | 3 | **21** | **166** | 496 | 0 |

Nenhuma requisição falhou com o dobro da meta, e a latência volta ao normal logo após o pico (veja os gráficos de percentis ao longo do tempo nos relatórios: [antes](reports/before-spike/index.html) · [depois](reports/after-spike/index.html)).

### Custo da stack de observabilidade (inconclusivo)

As rodadas da seção anterior correram depois do reinício do Docker, quando Prometheus, Grafana e Jaeger estavam parados. Com a stack completa no ar, a versão "depois" mediu:

| Amostragem de traces | p99 (rodada a) | p99 (rodada b) |
|---|---|---|
| 100% | 962 ([relatório](reports/after-full-load/index.html)) | 379 |
| 10% | 513 | 827 |

A dispersão entre rodadas idênticas (379 a 962 ms) é maior que qualquer diferença entre as configurações, então **não dá para atribuir o efeito à amostragem**. Ainda assim, as métricas da rodada com 100% mostram que, com a stack completa, cada conexão fica presa 7,2 ms em vez de 4,0, e o pool volta a saturar (espera média de 23,5 ms). Ou seja, numa VM compartilhada, as 3 ferramentas de observabilidade disputam CPU com o caminho da transação. A amostragem pode ser ajustada com `TRACE_SAMPLING` no Compose; o padrão continua 1.0 para que qualquer transferência da demo apareça no Jaeger.

## Conclusões

1. **O gargalo era o pool de conexões, e dimensioná-lo é a otimização com evidência mais clara:**
   - com a máquina calma, o p95 caiu de 120 para 25 ms e o p99 de 520 para 75 ms;
   - no spike, o p99 caiu de 446 para 166 ms;
   - a espera por conexão foi de 6,5 ms para 0,1 ms.
2. **A meta de p99 < 200 ms a 500 req/s foi atingida em 3 rodadas:** pool 20 com 75 ms (só a otimização 1), rodada 3 com 153 ms e spike com 166 ms. As demais rodadas com pool 20 e a máquina calma ficaram entre 305 e 962 ms, sempre com 0 erros. Nenhuma rodada com pool 10 atingiu a meta.
3. **A latência mediana é baixa e estável (3–4 ms).** O que varia é a cauda, e a cauda acompanha a disputa por CPU na máquina, não o código.
4. **Neste ambiente a meta não é garantida de forma reproduzível.** Para afirmar isso seria preciso uma máquina dedicada: banco e serviço em hosts separados e o gerador de carga fora da VM.

## O que eu faria a seguir

- Medir num ambiente dedicado: um runner de CI com recursos fixos, ou instâncias de nuvem separadas para app, banco e gerador de carga.
- **Banco:** aplicar o `reWriteBatchedInserts=true` do driver JDBC e juntar saldo+lock e limites+soma, se o tempo de conexão presa voltar a dominar.
- **Observabilidade:** amostragem baseada em cauda (tail sampling) no OpenTelemetry Collector, mantendo 100% dos traces com erro ou lentos e uma fração dos normais, em vez de amostragem fixa por cabeça.
- **Hot accounts:** conta muito disputada, como a de um grande varejista recebendo, serializa no lock pessimista ([ADR-002](../adr/0002-estrategia-de-locking.md)). A solução seria particionar o saldo em sub-contas ou acumular créditos de forma assíncrona. Este teste usa pares aleatórios e não exercita esse caso.
