"""Enrutador de conversación en 3 niveles (ADR-0004).

Decide el nivel ANTES de gastar tokens de un LLM caro:
  - Nivel 1: código puro (FAQ, saludos, horarios, precios). Coste cero.
  - Nivel 2: modelo local pequeño (aún no implementado en Fase 0).
  - Nivel 3: LLM potente (aún no implementado en Fase 0).

Fase 0: solo Nivel 1 con intents deterministas; los demás devuelven un mensaje de
cortesía indicando que llegarán en fases posteriores.
"""

from __future__ import annotations

from aetheria_ai.models.plan import ConversationRequest, ConversationResponse

# Intents deterministas de Nivel 1 (sin IA). Palabra clave -> respuesta.
_LEVEL1_INTENTS: dict[str, str] = {
    "hola": "¡Bienvenido a Aetheria! ¿En qué puedo ayudarte?",
    "horario": "Estoy disponible de día en el mundo. Vuelve cuando quieras.",
    "precio": "Ofrezco servicios inteligentes. Pregúntame por uno concreto.",
    "adios": "¡Hasta pronto! Que te vaya bien en Aetheria.",
}


def _match_level1(message: str) -> str | None:
    text = message.strip().lower()
    for keyword, reply in _LEVEL1_INTENTS.items():
        if keyword in text:
            return reply
    return None


async def handle_conversation(request: ConversationRequest) -> ConversationResponse:
    reply = _match_level1(request.message)
    if reply is not None:
        return ConversationResponse(reply=reply, level=1)

    # Niveles 2 y 3 llegan en Fase 3. Por ahora, respuesta segura de Nivel 1.
    return ConversationResponse(
        reply="Todavía estoy aprendiendo a conversar sobre eso. Vuelve pronto.",
        level=1,
    )
