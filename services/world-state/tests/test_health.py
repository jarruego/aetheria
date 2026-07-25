from fastapi.testclient import TestClient

from aetheria_world.main import app


def test_health_ok():
    # El lifespan intenta conectar a la DB; si no hay, degrada (db=False) sin caer.
    with TestClient(app) as client:
        resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "world-state"
    assert "db" in body
