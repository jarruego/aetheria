# Main (Paper)

Mundo **persistente**: aquí viven la economía, las ciudades, las parcelas y la IA.
Incluye el **plugin Aetheria**, que es quien ejecuta los planes ya validados.

- Acepta conexiones **solo desde Velocity**.
- El plugin habla con el backend por HTTP (contrato `contracts/openapi.yaml`).
- El mundo y los logs no se versionan (ver `.gitignore`); solo la configuración.

Se materializa en Fase 1; la integración plena con IA en Fase 3.
