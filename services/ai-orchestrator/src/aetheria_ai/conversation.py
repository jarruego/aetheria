"""Enrutador de conversacion en 3 niveles (ADR-0004, ADR-0007).

Decide el nivel ANTES de gastar tokens de un LLM caro:
  - Nivel 1: codigo puro (FAQ, saludos, horarios, precios). Coste cero.
  - Nivel 2: charla normal -> proveedor LOCAL (hoy stub). Nunca usa el LLM de pago.
  - Nivel 3: razonamiento complejo -> proveedor LLM_PROVIDER (por defecto stub, gratis).

La clasificacion es codigo determinista, no un prompt: nunca gastamos para decidir.
"""

from __future__ import annotations

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMMessage
from aetheria_ai.llm.factory import get_local_provider, get_provider
from aetheria_ai.models.plan import ConversationRequest, ConversationResponse

# Intents deterministas de Nivel 1 (sin IA). Palabra clave -> respuesta.
_LEVEL1_INTENTS: dict[str, str] = {
    "hola": "Bienvenido a Aetheria! En que puedo ayudarte?",
    "adios": "Hasta pronto! Que te vaya bien en Aetheria.",
    "gracias": "Un placer. Aqui estare.",
    "horario": "Estoy disponible de dia en el mundo. Vuelve cuando quieras.",
    "precio": "Ofrezco servicios inteligentes. Preguntame por uno concreto.",
    "pedido": "Puedo consultar el estado de tus encargos. Cual quieres revisar?",
}

# Pistas de que un mensaje merece el Nivel 3 (razonamiento).
_LEVEL3_HINTS = (
    "planifica", "planificar", "disena", "diseña", "construye", "construir",
    "por que", "por qué", "explica", "compara", "estrategia", "optimiza",
)

_SYSTEM_PROMPT = (
    "Eres un NPC de Aetheria, un mundo de Minecraft gobernado por IA. "
    "Responde breve, util y en el tono del mundo."
)


def _match_level1(message: str) -> str | None:
    text = message.strip().lower()
    for keyword, reply in _LEVEL1_INTENTS.items():
        if keyword in text:
            return reply
    return None


def classify_level(message: str) -> int:
    """Decide el nivel (1/2/3) de forma determinista."""
    if _match_level1(message) is not None:
        return 1
    text = message.strip().lower()
    if len(text) > 120 or any(h in text for h in _LEVEL3_HINTS):
        return 3
    return 2


async def handle_conversation(request: ConversationRequest) -> ConversationResponse:
    level = classify_level(request.message)

    if level == 1:
        reply = _match_level1(request.message)
        return ConversationResponse(reply=reply or "", level=1)

    # Nivel 2 (local/gratis) o Nivel 3 (LLM potente, por defecto stub).
    if level == 2:
        provider = get_local_provider()
        model = settings.llm_model_l2
    else:
        provider = get_provider()
        model = settings.llm_model_l3

    messages = [
        LLMMessage(role="system", content=_SYSTEM_PROMPT),
        LLMMessage(role="user", content=request.message),
    ]
    reply = await provider.complete(messages, model=model, max_tokens=300)
    return ConversationResponse(reply=reply, level=level)
