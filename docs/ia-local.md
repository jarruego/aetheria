# IA local a coste cero (Ollama)

Guía práctica para tener **IA real sin pagar nada**: el modelo corre en tu máquina, no
hay API key y los datos no salen del equipo.

> La decisión de diseño y el porqué de cada elección están en
> [ADR-0009](adr/0009-local-llm-ollama.md). Este documento es el **cómo**.

---

## 1. ¿Aguanta tu máquina?

Lo que manda **no es la CPU, es el ancho de banda de memoria**: generar cada token obliga
a recorrer el modelo entero. Por eso una GPU modesta gana por goleada a una CPU buena.

| Hardware | Ancho de banda | Modelo 4B | ¿Sirve? |
|---|---|---|---|
| GPU con ≥6 GB VRAM | ~300 GB/s | 50–60 tok/s | ✅ Sí |
| Mini PC Intel N100 | ~30 GB/s | 8–12 tok/s | ⚠️ Justo |
| Raspberry Pi 4 | ~4 GB/s | 1–2 tok/s | ❌ No |
| CPU con DDR4-2133 | ~34 GB/s | ~10 tok/s | ⚠️ Justo |

**Regla práctica**: si el modelo cabe entero en la VRAM, va bien. Si no cabe, se reparte
con la CPU y la velocidad se desploma.

Comprueba dónde corre realmente:

```bash
ollama ps
# La columna PROCESSOR debe decir "100% GPU"
```

---

## 2. Instalación

```powershell
winget install --id Ollama.Ollama -e
```

```bash
ollama pull gemma3:4b   # Nivel 2 — charla con NPC (3,3 GB)
ollama pull qwen3:8b    # Nivel 3 — planner (5,2 GB)
```

---

## 3. Configuración

En tu `.env`:

```ini
LLM_PROVIDER=ollama
LLM_MODEL_L3=qwen3:8b

LLM_LOCAL_PROVIDER=ollama
LLM_MODEL_L2=gemma3:4b

OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_TIMEOUT_S=120
```

Reinicia el orquestador:

```bash
docker compose up -d --build ai-orchestrator
```

### ⚠️ La trampa de Docker

Desde dentro de un contenedor, **`localhost` es el propio contenedor**, no tu PC. Para
llegar al Ollama del host hay que usar `host.docker.internal`.

| Dónde corre el orquestador | URL correcta |
|---|---|
| En Docker (lo normal) | `http://host.docker.internal:11434` |
| Directamente en Windows | `http://localhost:11434` |
| Ollama en otra máquina de tu red | `http://192.168.x.x:11434` |

---

## 4. Por qué un modelo distinto en cada nivel

No es capricho: **se midió**.

| Nivel | Modelo | Familia | Motivo |
|---|---|---|---|
| **2** — charla NPC | `gemma3:4b` | Instruct puro | Alta frecuencia, respuestas cortas. Rápido y directo. |
| **3** — planner JSON | `qwen3:8b` | Razonamiento | Baja frecuencia, exige precisión estructural. |

**No pongas un modelo de razonamiento (Qwen3, DeepSeek-R1) en el Nivel 2.** Se pone a
"pensar en voz alta" en una charla trivial, tarda más y divaga. Es exactamente lo
contrario de lo que quieres cuando un aldeano te saluda.

---

## 5. Por qué esto es seguro aunque el modelo sea malo

Un modelo de 4B se equivoca bastante más que Claude. **Y da igual.**

El flujo de seguridad (ADR-0004, `docs/architecture/security-flow.md`) es:

```
modelo → propone plan JSON → VALIDADOR DETERMINISTA → plugin ejecuta lista blanca
```

El validador rechaza cualquier plan malformado, con acciones fuera de la lista blanca o
con parámetros inválidos, **antes** de que llegue al mundo. Un modelo pequeño sólo puede
fallar y ser rechazado; no puede romper nada.

De hecho, **probar con un modelo mediocre es mejor test que probar con Claude**: estresa
el validador de verdad, que es la pieza que más te conviene tener bien probada.

---

## 6. Problemas frecuentes

### El NPC no responde / respuesta vacía

Casi siempre es el **modo razonamiento**: el modelo gasta todos los tokens pensando y no
llega a escribir. El proveedor ya envía `reasoning_effort: "none"` y detecta este caso
con un error explicativo. Si aun así ocurre, sube `max_tokens` o usa un modelo instruct.

### "No se pudo conectar con el servidor LLM local"

1. ¿Está Ollama arrancado? → `ollama ps`
2. ¿Estás usando `localhost` desde un contenedor? → cámbialo a `host.docker.internal`

### "model not found"

Falta descargarlo: `ollama pull <modelo>`

### Va lentísimo (segundos por palabra)

El modelo no cabe en la VRAM y está corriendo en CPU. Comprueba con `ollama ps` que
`PROCESSOR` diga `100% GPU`. Si no, usa un modelo más pequeño o reduce el contexto:

```powershell
$env:OLLAMA_CONTEXT_LENGTH = "4096"
```

### La primera respuesta tarda mucho, luego va rápido

Normal: es la carga del modelo en VRAM (medido: ~40 s en frío, ~2 s en caliente). Ollama
lo mantiene cargado unos minutos tras el último uso.

---

## 7. Limitaciones que conviene tener claras

- **No sirve en el despliegue cloud.** La instancia ARM del free tier de Oracle no tiene
  GPU. Allí toca `stub` o pagar por API. Ver `docs/infra/fase4-oracle-handoff.md`.
- **Ata el orquestador a la máquina** que tenga el modelo (aunque puede ser otra de tu
  red, es sólo una URL).
- **Calidad inferior a Claude**, sobre todo generando JSON complejo. Para probar el
  sistema sobra; para producción con NPC convincentes, no es lo mismo.

---

## 8. Alternativa: capas gratuitas de API

Si no quieres usar tu GPU, hay proveedores con capa gratuita real (Google AI Studio,
Groq y otros). Son más rápidos y no ocupan tu equipo, pero exigen una API key y **tus
conversaciones salen de tu máquina**. Requeriría un adaptador nuevo, igual de sencillo
que el de Ollama gracias al ADR-0004.
