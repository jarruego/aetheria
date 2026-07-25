# syntax=docker/dockerfile:1
FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencias primero (mejor cacheo de capas)
COPY pyproject.toml ./
RUN pip install --upgrade pip && pip install ".[anthropic]"

# Código
COPY src ./src

EXPOSE 8090

# Healthcheck alineado con GET /health
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8090/health').status==200 else 1)"

CMD ["uvicorn", "aetheria_ai.main:app", "--host", "0.0.0.0", "--port", "8090", "--app-dir", "src"]
