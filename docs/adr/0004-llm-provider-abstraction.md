# ADR-0004: Abstracción de proveedor LLM y conversación en 3 niveles

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

Requisito explícito: poder cambiar Claude por OpenAI (o cualquier otro modelo) sin
tocar Minecraft. Además, no se deben gastar tokens de un LLM caro en interacciones
triviales (saludos, horarios, precios).

## Decisión

### 1. Adaptador de proveedor desacoplado

El AI Orchestrator define una interfaz `LLMProvider` (ver
`services/ai-orchestrator/src/aetheria_ai/llm/base.py`). Cada proveedor concreto
(`AnthropicProvider`, `OpenAIProvider`, `LocalProvider`) la implementa. La selección se
hace por configuración (`LLM_PROVIDER` en `.env`), mediante una factory. Ningún otro
servicio —y desde luego no el plugin— conoce qué proveedor está activo.

### 2. Conversación en 3 niveles (enrutado antes de gastar tokens)

- **Nivel 1 (código, sin IA):** intents deterministas — FAQ, horarios, saludos,
  precios, estado de pedidos. Coste cero.
- **Nivel 2 (modelo local pequeño):** conversación normal. Coste bajo, sin API externa.
- **Nivel 3 (LLM potente):** razonamiento complejo, generación de planes. Solo cuando
  el enrutador lo justifica.

El enrutador (código, no prompt) decide el nivel según el intent detectado.

### Modelos por defecto (configurables)

| Nivel | Variable | Modelo por defecto |
|---|---|---|
| Nivel 3 | `LLM_MODEL_L3` | `claude-sonnet-4-6` |
| Nivel 2 | `LLM_MODEL_L2` | `claude-haiku-4-5-20251001` (o modelo local) |

Para planificación especialmente compleja puede elegirse `claude-opus-4-8` vía config.

## Consecuencias

- (+) Cambiar de proveedor = cambiar una variable de entorno + implementar el adaptador.
- (+) Coste controlado: la mayoría de interacciones no llegan al Nivel 3.
- (-) Mantener varios adaptadores al día. Se mitiga con una interfaz mínima y tests de
  contrato por proveedor.
