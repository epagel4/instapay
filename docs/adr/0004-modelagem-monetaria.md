# ADR-004 — Modelagem monetária

- **Status:** Aceito
- **Data:** 2026-11-01

## Contexto

`double`/`float` não representam 0,10 exatamente; somas acumulam erro e quebram a invariante do ledger (soma dos lançamentos = 0). Precisamos de uma representação exata, rápida (meta p99 < 200 ms a 500 req/s) e com arredondamento sempre explícito.

O Pix opera exclusivamente em BRL, com 2 casas decimais.

## Decisão

`Money` é um value object (`record Money(long cents)`) no domínio:

- **Armazenamento interno em centavos (`long`).** Soma/subtração são aritmética inteira exata; `long` comporta ±92 quatrilhões de reais.
- **Overflow nunca é silencioso:** operações usam `Math.addExact`/`subtractExact`/`negateExact`; estouro vira `DomainException("money.overflow")`.
- **Conversão de `BigDecimal` exata por padrão:** `Money.of("10.005")` falha. Arredondar exige dizer como: `Money.of(valor, RoundingMode.HALF_EVEN)`. Não existe arredondamento implícito em lugar nenhum.
- **Sinal permitido.** Lançamentos do ledger (Fase 3) são valores com sinal, o que torna "soma = 0" verificável diretamente. Regras que exigem valor positivo (ex.: transferência) validam por conta própria.
- **Moeda única (BRL), implícita.** Não há campo de moeda.
- **Persistência:** coluna `BIGINT` em centavos. **API:** valor como string decimal (`"25.00"`), nunca número JSON, para não passar por ponto flutuante em clientes.
- **ArchUnit** proíbe campos `double`/`float`/`Double`/`Float` no domínio.

## Alternativas consideradas

- **`BigDecimal` como representação interna:** exato e flexível, mas exige cuidado com escala em `equals` (`10.0` ≠ `10.00`), aloca a cada operação e é mais lento. Fica restrito às bordas (parse/format).
- **Moneta (JSR 354):** suporte multi-moeda que não precisamos, mais uma dependência no domínio puro.
- **`long` cru sem value object:** exato, mas sem tipagem — fácil somar centavos com outro `long` qualquer e sem validação de escala.

## Consequências

- Aritmética exata e barata; invariantes do ledger testáveis por igualdade simples.
- Multi-moeda ou frações de centavo (ex.: juros com 4+ casas) exigiriam revisar este ADR — fora do escopo do Pix.
- Divisões (rateios, estorno proporcional) precisam de regra explícita de distribuição de centavos residuais quando surgirem.
