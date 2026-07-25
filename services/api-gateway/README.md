# API Gateway

Punto de entrada REST del plugin de Minecraft. **No contiene lógica de IA**: autentica,
valida esquemas y reenvía al AI Orchestrator.

## Endpoints (ver `contracts/openapi.yaml`)

| Método | Ruta | Auth | Descripción |
|---|---|---|---|
| GET | `/health` | no | Salud del servicio |
| POST | `/v1/conversation` | Bearer | Mensaje a un NPC (respuesta en 3 niveles) |
| POST | `/v1/plans` | Bearer | Solicitar un plan (devuelto ya validado) |

Autenticación: `Authorization: Bearer <INTERNAL_SERVICE_TOKEN>`.

## Desarrollo

```bash
pip install -e ".[dev]"
uvicorn aetheria_api.main:app --app-dir src --port 8080 --reload
pytest
```

El gateway reenvía a `AI_ORCHESTRATOR_URL` (por defecto el servicio `ai-orchestrator`
en la red de docker-compose).
