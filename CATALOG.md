# Demo catalog — real applications

**The bar for this repo:** each demo is a deep, **real application** over a realistic domain (like
`PersonalFinanceDemo` or `DataAnalystDemo`), weaving many capabilities together — not a single-feature
skeleton. Focused, single-capability snippets live as **examples** in the main repo instead:
[`java-ai-agent/examples`](https://github.com/vaiju1981/java-ai-agent/tree/main/examples) (RAG, memory
windowing, skills, eval, token cost, multimodal, …).

## Real applications here

| Demo | Domain | Capabilities exercised |
|---|---|---|
| `GovernedSupportDeskDemo` | support desk (scripted) | governed runtime, durable store, guardrails, budget, tenant isolation, audit |
| `MultiAgentNewsroomDemo` | editorial pipeline | GraphAgent, A2A, agents-as-tools, group chat, `@AiService` |
| `DataAnalystDemo` | e-commerce warehouse | `@AgentTool` EDA toolkit, SQL, JSON-schema validation, structured report |
| `FraudInvestigationDemo` | payments fraud | effectful tools, graduated authorization, human-in-the-loop, idempotency |
| `PersonalFinanceDemo` | personal finances | advisory toolkit, anomaly/subscription detection, persisted plan |

## Real-app roadmap (each weaves several capabilities)

| App | Showcases | Status |
|---|---|---|
| **Support Copilot** | RAG over a product KB + governed effectful tools (ticket/refund) + guardrails + memory + **self-learning** from past resolutions (`JdbcEpisodicStore`) | planned — needs **0.5.0** on Central |
| **Research Assistant** | deep/DAG planning + concurrent sub-agents + RAG + checkpoint resume → a cited briefing | planned |
| **Coding/DevOps assistant** | RAG over code+docs, tools to run checks, structured findings | idea |

Order: **Support Copilot** first (it houses the self-learning showcase) once 0.5.0 publishes, then
Research Assistant. Each ships as its own PR with deterministic tests for its domain logic (as the
current real apps do).
