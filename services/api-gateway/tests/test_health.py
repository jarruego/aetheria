from fastapi.testclient import TestClient

from aetheria_api.main import app

client = TestClient(app)


def test_health_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "api-gateway"


def test_conversation_requires_auth():
    resp = client.post(
        "/v1/conversation",
        json={"npc_id": "n1", "player_id": "p1", "message": "hola"},
    )
    assert resp.status_code == 401
