# ADR-0007: Defaults de coste cero y enrutado de niveles a prueba de gasto

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

Queremos ejercitar todo el pipeline de IA (adaptador LLM, conversacion 3 niveles,
planner) sin gastar dinero mientras el proyecto es pequeno. Ademas, cuando se conecte un
LLM real, la conversacion cotidiana no debe generar coste sorpresa.

## Decision

### 1. Proveedor `stub` por defecto (coste cero)

`LLM_PROVIDER=stub` es el valor por defecto. `StubProvider` implementa la interfaz
`LLMProvider` y devuelve una respuesta simulada determinista, sin llamar a ninguna API.
Todo el sistema funciona y se testea end-to-end sin gastar. Activar Claude = cambiar
`LLM_PROVIDER=claude` y poner `ANTHROPIC_API_KEY`.

### 2. Enrutado de niveles a prueba de gasto

- **Nivel 1** (FAQ, saludos, precios...): codigo puro, coste cero.
- **Nivel 2** (charla normal): usa SIEMPRE un proveedor **local/gratuito**
  (`get_local_provider()`, hoy stub). Nunca toca el LLM de pago.
- **Nivel 3** (razonamiento complejo): usa `LLM_PROVIDER` (por defecto stub).

Asi, aunque se configure `LLM_PROVIDER=claude`, solo los mensajes clasificados como
Nivel 3 pueden generar coste; la conversacion cotidiana (Nivel 2) sigue siendo gratis.
La clasificacion de nivel es **codigo determinista**, no un prompt: no se gasta ni para
decidir el nivel.

### 3. El planner no llama al LLM por defecto

`build_plan` usa el contexto del world-state (barato, estructurado) y produce un plan
determinista. Deja marcado el punto de enganche para que un LLM proponga acciones; esa
ruta es opcional y, cuando se active, su salida seguira pasando por el validador.

## Consecuencias

- (+) Desarrollo y demos sin coste; nada que pagar hasta decidirlo.
- (+) Sin gasto sorpresa: el Nivel 2 nunca escala a un LLM de pago.
- (+) Cambiar a IA real = una variable de entorno + una API key.
- (-) Con `stub`, las respuestas no son "inteligentes" (es lo esperado en dev).
- Cuando exista un modelo local para el Nivel 2, `get_local_provider()` lo devolvera en
  lugar del stub, sin tocar el resto del codigo.
