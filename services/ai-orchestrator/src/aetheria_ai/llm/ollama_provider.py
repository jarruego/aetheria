"""Proveedor LLM local vía Ollama (coste cero, ADR-0004 / ADR-0009).

Habla el endpoint **compatible con OpenAI** (`/v1/chat/completions`) en lugar del
nativo de Ollama. Así este mismo adaptador vale para cualquier runtime local que
exponga ese formato (Ollama, LM Studio, vLLM, llama.cpp server): cambiar de motor
es cambiar `OLLAMA_BASE_URL`, sin tocar código.

No añade dependencias: `httpx` ya es dependencia principal del servicio.

Coste: CERO. El modelo corre en la máquina del dueño, no hay API de pago.
"""

from __future__ import annotations

import re

import httpx

from aetheria_ai.llm.base import LLMMessage, LLMProvider

# Los modelos de razonamiento (Qwen3, DeepSeek-R1...) emiten su reflexion interna
# entre etiquetas <think>. Si eso llegara al planner romperia el parseo del JSON del
# plan, asi que se elimina AQUI: ningun otro componente necesita saber que existe.
_THINK_BLOCK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)


def _strip_reasoning(text: str) -> str:
    return _THINK_BLOCK.sub("", text).strip()


class OllamaProvider(LLMProvider):
    name = "ollama"

    def __init__(self, base_url: str, timeout_s: float = 120.0) -> None:
        # Sin barra final: las rutas se concatenan explicitamente.
        self._base_url = base_url.rstrip("/")
        self._timeout_s = timeout_s

    async def complete(
        self,
        messages: list[LLMMessage],
        *,
        model: str,
        max_tokens: int = 1024,
        temperature: float = 0.7,
    ) -> str:
        payload = {
            "model": model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
            # CRITICO con modelos de razonamiento (Qwen3, DeepSeek-R1...).
            # Sin esto, el modelo gasta TODO el presupuesto de tokens "pensando" y
            # devuelve content vacio con finish_reason=length. Verificado en real:
            # con qwen3:4b y 200 tokens, el 100% se fue en razonamiento.
            # Nota: 'chat_template_kwargs.enable_thinking' NO funciona en Ollama;
            # 'reasoning_effort' si. Los modelos sin razonamiento lo ignoran.
            "reasoning_effort": "none",
        }

        url = f"{self._base_url}/v1/chat/completions"
        try:
            async with httpx.AsyncClient(timeout=self._timeout_s) as client:
                resp = await client.post(url, json=payload)
                resp.raise_for_status()
                data = resp.json()
        except httpx.ConnectError as exc:
            raise RuntimeError(
                f"No se pudo conectar con el servidor LLM local en {self._base_url}. "
                "Comprueba que Ollama esta arrancado ('ollama ps'). "
                "Desde un contenedor Docker la URL NO es localhost: usa "
                "http://host.docker.internal:11434"
            ) from exc
        except httpx.TimeoutException as exc:
            raise RuntimeError(
                f"El modelo local '{model}' tardo mas de {self._timeout_s:.0f}s en responder. "
                "Suele significar que no cabe en la VRAM y se esta ejecutando en CPU. "
                "Prueba un modelo mas pequeno o reduce el contexto (OLLAMA_CONTEXT_LENGTH)."
            ) from exc
        except httpx.HTTPStatusError as exc:
            detail = exc.response.text[:300]
            raise RuntimeError(
                f"El servidor LLM local devolvio {exc.response.status_code}: {detail}. "
                f"Si dice 'model not found', descargalo con: ollama pull {model}"
            ) from exc

        try:
            choice = data["choices"][0]
            message = choice["message"]
            content = message.get("content")
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(
                f"Respuesta inesperada del servidor LLM local: {str(data)[:300]}"
            ) from exc

        # Ollama devuelve el razonamiento en un campo APARTE ('reasoning'), no dentro
        # de content. Si content viene vacio y hay razonamiento, el modelo agoto el
        # presupuesto pensando: fallar con un mensaje util es mejor que devolver "" y
        # que un NPC se quede mudo sin explicacion.
        if not (content or "").strip() and message.get("reasoning"):
            raise RuntimeError(
                f"El modelo '{model}' consumio los {max_tokens} tokens razonando y no "
                "llego a responder (finish_reason="
                f"{choice.get('finish_reason')!r}). Sube max_tokens o comprueba que el "
                "servidor respeta reasoning_effort='none'."
            )

        return _strip_reasoning(content or "")
