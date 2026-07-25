# Velocity (proxy)

Único punto de entrada de la red (ADR-0005). Aquí vivirán:

- `velocity.toml` con **modern forwarding** (secreto `VELOCITY_FORWARDING_SECRET`).
- Registro de servidores backend: `lobby`, `main` (y futuros mundos).
- **Geyser** + **Floodgate** para aceptar Bedrock (móvil, consola).

Se materializa en Fase 1. El firewall solo expondrá el puerto de Velocity (Java) y el
de Geyser (Bedrock).
