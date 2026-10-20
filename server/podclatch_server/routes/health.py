from fastapi import APIRouter

router = APIRouter()


@router.get("/healthz")
def healthz() -> dict[str, str]:
    """Unauthenticated liveness check."""
    return {"status": "ok"}
