# ADR-0009: IA real a coste cero con un modelo local (Ollama)

- **Estado**: Aceptada
- **Fecha**: 2026-07-26
- **Contexto previo**: ADR-0004 (abstracción de proveedor LLM), ADR-0007 (coste cero
  por defecto y enrutado por niveles)

## Contexto

Hasta ahora sólo había dos opciones para el LLM:

- `stub` — **coste cero**, pero devuelve respuestas simuladas. Sirve para ejercitar el
  pipeline, no para saber si el sistema *funciona de verdad*.
- `claude` — respuestas reales, pero **de pago**, y el dueño quiere validar el sistema
  completo antes de gastar un céntimo.

Faltaba el punto intermedio: **IA real, gratis**. Sin ella no se puede responder a
preguntas como "¿el planner genera JSON que el validador acepta cuando el modelo no es
perfecto?", que es justo lo que hay que probar antes de pagar por nada.

## Decisión

Se añade un tercer proveedor, **`ollama`**, que ejecuta un modelo de lenguaje en la
máquina del dueño. Coste: **cero**. Los datos no salen del equipo.

### 1. Se habla el protocolo de OpenAI, no el nativo de Ollama

El proveedor llama a `POST {base}/v1/chat/completions`, el endpoint compatible con
OpenAI que expone Ollama — **no** su API nativa `/api/chat`.

**Por qué**: así el mismo adaptador vale para cualquier runtime local que hable ese
formato (Ollama, LM Studio, vLLM, llama.cpp server). Cambiar de motor es cambiar
`OLLAMA_BASE_URL`, sin tocar código. Es la misma lógica del ADR-0004 aplicada un nivel
más abajo.

**Contrapartida aceptada**: se pierde acceso a opciones propias de Ollama (`num_ctx`,
`keep_alive`) que sólo existen en la API nativa. Se controlan por variables de entorno
del servidor Ollama en su lugar.

### 2. Un modelo distinto por nivel, y de familia distinta a propósito

| Nivel | Modelo por defecto | Familia | Por qué |
|---|---|---|---|
| **2** — charla con NPC | `gemma3:4b` | **Instruct puro** | Alta frecuencia, respuestas cortas. Rápido y no divaga. |
| **3** — planner → JSON | `qwen3:8b` | **Razonamiento** | Baja frecuencia, exige precisión estructural. |

Esto **no es arbitrario, se midió** (ver sección de verificación). Un modelo de
razonamiento en el Nivel 2 es contraproducente: se pone a "pensar en voz alta" en una
conversación casual, gasta tokens y tarda más. Un modelo instruct puro en el Nivel 3
falla más generando la estructura del plan.

### 3. El razonamiento se desactiva explícitamente

El proveedor envía siempre `reasoning_effort: "none"`.

**Por qué es crítico**: sin ese parámetro, un modelo de razonamiento consume **todo** el
presupuesto de tokens razonando y devuelve una respuesta vacía con
`finish_reason: length`. Comprobado en real: con `qwen3:4b` y 200 tokens, el 100% se fue
en razonamiento y `content` llegó vacío.

Se probaron tres mecanismos; sólo uno funciona:

| Mecanismo | Resultado |
|---|---|
| `chat_template_kwargs: {enable_thinking: false}` | ❌ Ollama lo ignora |
| **`reasoning_effort: "none"`** | ✅ Funciona |
| `/api/chat` nativo con `think: false` | ⚠️ Funciona, pero rompe la portabilidad (decisión 1) |

Además, Ollama **no devuelve el razonamiento dentro de `content`**: usa un campo aparte
llamado `reasoning`. Si `content` viene vacío y `reasoning` tiene contenido, el proveedor
**falla con un mensaje explicativo** en vez de devolver cadena vacía — un NPC mudo sin
motivo aparente es mucho peor de depurar que un error claro.

Se mantiene además un filtro de bloques `<think>...</think>` como defensa para runtimes
que sí los incrustan en el texto.

### 4. El Nivel 2 rechaza proveedores de pago por código

`get_local_provider()` lanza `ValueError` si se le configura `claude` u `openai`.

**Por qué**: el ADR-0007 ya establecía que el Nivel 2 nunca gasta, pero eso se cumplía
sólo porque la función devolvía `StubProvider` fijo. Al darle opciones, una variable de
entorno mal puesta bastaría para facturar en **cada frase de cada NPC**. La regla pasa de
ser documentación a ser código ejecutable.

## Consecuencias

**Positivas**

- IA real sin coste, sin API key y sin que los datos salgan de la máquina.
- **Mejor test del validador que con Claude**: un modelo pequeño se equivoca más, así que
  ejercita de verdad la barrera de seguridad. Que el modelo falle es *el escenario para el
  que se diseñó* el flujo del ADR-0004 — el validador determinista rechaza cualquier plan
  malformado antes de que llegue al plugin. Un modelo mediocre **no puede romper nada**.
- Cero dependencias nuevas: `httpx` ya era dependencia principal del servicio.
- El adaptador sirve para otros runtimes locales sin cambios.

**Negativas / limitaciones**

- **Ata el orquestador a una máquina concreta.** El servicio deja de ser reubicable
  libremente si depende de un Ollama local, lo cual roza el ADR-0003. Mitigación: sigue
  siendo una URL configurable, así que el modelo puede vivir en otra máquina de la red.
- **No sirve en el despliegue cloud actual.** La instancia ARM del free tier de Oracle
  (2 OCPU / 12 GB, sin GPU) no puede con esto. En cloud hay que seguir con `stub` o pagar
  por API. Ver `docs/infra/fase4-oracle-handoff.md`.
- **Depende del hardware del dueño.** Sin GPU con VRAM suficiente, la inferencia cae a la
  CPU y se vuelve inservible para un juego (unidades de tokens por segundo).
- Los modelos ocupan disco (2,5–5 GB cada uno) y hay que descargarlos aparte.

## Verificación (medido, no estimado)

Equipo: **Ryzen 7 3700X, 64 GB RAM, GTX 1660 Ti (6 GB VRAM)**.

- Ollama confirma **100% GPU** para modelos de 4B (3,2 GB en VRAM, contexto 4096).
- Velocidad en caliente: **48–60 tokens/s**.
- **Nivel 2** con `gemma3:4b` → respuesta en personaje y en español, correcta.
- **Nivel 3** con `qwen3:8b` → JSON válido con la forma exacta que espera el validador
  (11,3 s en caliente).
- Prueba ejecutada **desde un contenedor** vía `host.docker.internal`, es decir por el
  mismo camino de red que usará en producción.
- 35 tests pasan; 14 nuevos, ninguno toca la red (transporte `httpx` simulado).

## Cómo se usa

```bash
ollama pull gemma3:4b   # Nivel 2
ollama pull qwen3:8b    # Nivel 3
```

En `.env`:

```ini
LLM_PROVIDER=ollama
LLM_MODEL_L3=qwen3:8b
LLM_LOCAL_PROVIDER=ollama
LLM_MODEL_L2=gemma3:4b
OLLAMA_BASE_URL=http://host.docker.internal:11434
```

⚠️ **Trampa de Docker**: desde dentro de un contenedor, `localhost` es el propio
contenedor, no tu máquina. Hay que usar `host.docker.internal`. Fuera de Docker (tests,
ejecución directa) es `http://localhost:11434`.

Guía completa: `docs/ia-local.md`.
