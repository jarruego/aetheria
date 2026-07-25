# ADR-0005: Velocity como único punto de entrada de la red

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

La red tendrá múltiples servidores (Lobby, Main, y en el futuro otros continentes,
creativo, eventos, pruebas). Los jugadores llegan desde Java y desde Bedrock (móvil,
consola) vía Geyser. Necesitamos un único punto de entrada, seguridad, y capacidad de
añadir mundos sin cambiar la arquitectura.

## Decisión

**Velocity** es el único punto de entrada. Reglas:

- Ningún servidor Paper es accesible directamente desde Internet. El firewall solo
  expone el puerto de Velocity (y el de Bedrock vía Geyser/Floodgate).
- Se usa **modern forwarding** con secreto compartido (`VELOCITY_FORWARDING_SECRET`);
  Paper se configura para aceptar conexiones solo desde Velocity.
- **Geyser + Floodgate** viven en/junto a Velocity para aceptar Bedrock, Android, iOS,
  Windows Bedrock, Xbox y (cuando sea posible) Switch/PlayStation.
- Añadir un mundo nuevo = registrar un backend más en Velocity. No cambia nada aguas
  arriba.

## Consecuencias

- (+) Superficie de ataque mínima; Paper protegido.
- (+) Añadir mundos es incremental y sin fricción.
- (+) Compatibilidad Java + Bedrock centralizada.
- (-) Velocity es un punto crítico: debe monitorizarse y, al crecer, considerarse alta
  disponibilidad (varias instancias tras un balanceador).
