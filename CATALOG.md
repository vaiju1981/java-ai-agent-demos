# Demo catalog — capability coverage

A map of every `java-ai-agent` capability to a demo, so the showcase is exhaustive. ✅ = a dedicated
demo here; 🟡 = touched by a broader demo (or shown in the main repo's `examples/` / `production-reference`);
⬜ = gap to build. "Released" notes whether it needs an unpublished version.

## Covered

| Capability | Demo | |
|---|---|---|
| Governed runtime, deny-by-default tools, audit, tenant isolation | `GovernedSupportDeskDemo` | ✅ |
| Durable conversation store (JDBC), restart survival | `GovernedSupportDeskDemo` | ✅ |
| Guardrails (crisis block, PII scrub) | `GovernedSupportDeskDemo` | ✅ |
| Token budget (graceful stop) | `GovernedSupportDeskDemo` | ✅ |
| `@AgentTool` methods + JSON-schema validation | `DataAnalystDemo`, `PersonalFinanceDemo` | ✅ |
| Effectful tools + graduated authorization + human-in-the-loop | `FraudInvestigationDemo` | ✅ |
| Turn idempotency | `FraudInvestigationDemo` | ✅ |
| Multi-agent: GraphAgent (conditional edge/cycle) | `MultiAgentNewsroomDemo` | ✅ |
| A2A (server + remote agent) | `MultiAgentNewsroomDemo` | ✅ |
| Agents-as-tools | `MultiAgentNewsroomDemo` | ✅ |
| Group chat (speaker selection) | `MultiAgentNewsroomDemo` | ✅ |
| `@AiService` typed facade | `MultiAgentNewsroomDemo` | 🟡 |
| Deep agent / DAG planner | `DagResumeDemo` | ✅ |
| Checkpoint crash-resume | `DagResumeDemo` | ✅ |
| Spring Boot starter, SSE streaming over HTTP | main repo `production-reference` | 🟡 |

## Gaps to build (batches)

| # | Capability | Planned demo | Runs on | Released |
|---|---|---|---|---|
| 1 | **RAG** — ingest, chunk, embed, retrieve, ground | `RagKnowledgeBaseDemo` | Ollama | 0.4.0 |
| 1 | **Structured output** — typed final result | `StructuredOutputDemo` | Ollama | 0.4.0 |
| 1 | **Smarter memory** — token-windowed + summarizing | `MemoryStrategiesDemo` | Ollama | 0.4.0 |
| 2 | **Self-learning** — ReflectiveAgent + durable episodic recall | `SelfLearningDemo` | Ollama | **0.5.0** (JdbcEpisodicStore) |
| 2 | **Skills** — acquire/select reusable skills | `SkilledAgentDemo` ✅ | Ollama | 0.4.0 |
| 2 | **Eval** — score an agent against a suite | `EvalHarnessDemo` ✅ | Ollama | 0.4.0 |
| 3 | **Observability** — token accounting + per-model cost; OTel/Micrometer | `ObservabilityDemo` ✅ | none | 0.4.0 |
| 3 | **Multimodal** — image → vision model | `MultimodalDemo` ✅ | Ollama vision (e.g. `llava`) | 0.4.0 |
| 4 | **MCP** — expose an MCP server's tools | `McpToolsDemo` | a local MCP server | 0.4.0 |
| 4 | **Provider swap** — same agent on Claude/OpenAI/Ollama | `ProviderSwapDemo` | API key (or Ollama) | 0.4.0 |

Order: batch 1 (RAG, structured, memory) → batch 2 (self-learning once 0.5.0 is published, skills, eval)
→ batch 3 (observability, multimodal) → batch 4 (MCP, provider swap). Each batch is its own PR; every demo
runs against a local Ollama model unless noted.
