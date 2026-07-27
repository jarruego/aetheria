"""Saneo del texto que acaba en el chat del juego.

Todo texto que llega a una accion SAY procede, directa o indirectamente, de algo que
escribio un jugador (su objetivo, su mensaje, o la respuesta de un LLM influida por
ellos). Nada de eso es de fiar.

Este modulo vive en `validator/` a proposito: el saneo forma parte de la barrera de
seguridad, no del planner. Asi protege TODOS los caminos —incluida la generacion por
LLM el dia que se active— y no solo el planner determinista actual.

Amenazas que cubre:

1. **Suplantacion de mensajes del servidor.** Minecraft interpreta los codigos de
   formato (`§c`, `&l`...). Sin filtrarlos, un jugador puede hacer que un NPC escriba
   algo con la pinta exacta de un aviso oficial del servidor.
2. **Inyeccion de lineas falsas.** Un salto de linea en el texto permite fabricar lo
   que parece un mensaje independiente en el chat.
3. **Spam / desbordamiento.** Textos enormes que inundan el chat.
"""

from __future__ import annotations

import re

# Tope de longitud de una respuesta de NPC. Los mensajes que ENVIA el servidor (no los que
# teclea el jugador) no tienen el limite de 256 del cliente: se ajustan en varias lineas. 200 era
# demasiado corto y cortaba respuestas normales con "...". 500 deja hablar al NPC (2-4 frases) sin
# permitir que inunde el chat.
MAX_CHAT_LENGTH = 500

# Codigos de formato de Minecraft: seccion (§) o ampersand (&) + digito/letra valida.
_FORMAT_CODES = re.compile(r"[§&][0-9a-fk-orA-FK-OR]")

# Caracteres de control, incluidos \n, \r y \t: permiten falsificar lineas de chat.
_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f-\x9f]")

_WHITESPACE = re.compile(r"\s+")


def sanitize_chat_text(text: str) -> str:
    """Devuelve una version del texto segura para enviar al chat del juego.

    Nunca lanza: si la entrada no es utilizable, devuelve cadena vacia y es el validador
    quien decide rechazar el plan (regla 'SAY sin texto').
    """
    if not isinstance(text, str):
        return ""

    clean = _FORMAT_CODES.sub("", text)
    clean = _CONTROL_CHARS.sub(" ", clean)
    clean = _WHITESPACE.sub(" ", clean).strip()

    if len(clean) > MAX_CHAT_LENGTH:
        # Se corta en el ultimo espacio para no partir una palabra por la mitad.
        recorte = clean[:MAX_CHAT_LENGTH]
        corte = recorte.rfind(" ")
        if corte > MAX_CHAT_LENGTH // 2:
            recorte = recorte[:corte]
        clean = recorte.rstrip() + "..."

    return clean
