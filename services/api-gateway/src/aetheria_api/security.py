"""Autenticación servicio-a-servicio por token bearer.

El plugin envía `Authorization: Bearer <INTERNAL_SERVICE_TOKEN>`. Es una barrera mínima
para Fase 0; en producción se complementa con red privada y rotación de secretos.
"""

from __future__ import annotations

import secrets

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from aetheria_api.config import settings

_bearer = HTTPBearer(auto_error=False)


async def require_internal_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> None:
    if credentials is None or not secrets.compare_digest(
        credentials.credentials, settings.internal_service_token
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token de servicio ausente o inválido.",
        )
