"""Custom exception definitions and error handlers for iTantra backend."""

from fastapi import Request, status
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError


class iTantraException(Exception):
    """Base exception for iTantra application errors."""
    def __init__(self, message: str, status_code: int = status.HTTP_400_BAD_REQUEST, error_type: str = "bad_request"):
        self.message = message
        self.status_code = status_code
        self.error_type = error_type
        super().__init__(message)


class UnsupportedLanguageError(iTantraException):
    """Raised when a requested language is unknown or unsupported for the operation."""
    def __init__(self, language: str, component: str = "general"):
        super().__init__(
            message=f"Language '{language}' is not supported for {component}. Use GET /api/languages to check supported languages.",
            status_code=status.HTTP_400_BAD_REQUEST,
            error_type="unsupported_language",
        )


class AudioValidationError(iTantraException):
    """Raised when uploaded audio is invalid, empty, or unparseable."""
    def __init__(self, detail: str):
        super().__init__(
            message=detail,
            status_code=status.HTTP_400_BAD_REQUEST,
            error_type="invalid_audio",
        )


class ModelUnavailableError(iTantraException):
    """Raised when an AI model is not loaded or failed during inference."""
    def __init__(self, detail: str):
        super().__init__(
            message=detail,
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            error_type="model_unavailable",
        )


async def itantra_exception_handler(request: Request, exc: iTantraException) -> JSONResponse:
    """Handle all iTantra custom exceptions cleanly without leaking internal traces."""
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "error": exc.error_type,
            "detail": exc.message,
            "status_code": exc.status_code,
        },
    )


async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    """Format FastAPI request validation errors into a clean, unified response."""
    errors = exc.errors()
    first_msg = errors[0]["msg"] if errors else "Invalid request data"
    loc = " -> ".join([str(l) for l in errors[0].get("loc", [])]) if errors else ""
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={
            "error": "validation_error",
            "detail": f"{loc}: {first_msg}" if loc else first_msg,
            "status_code": status.HTTP_422_UNPROCESSABLE_ENTITY,
        },
    )


async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Catch-all for unhandled exceptions, hiding tracebacks from clients."""
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "error": "internal_server_error",
            "detail": "An unexpected error occurred processing your request.",
            "status_code": status.HTTP_500_INTERNAL_SERVER_ERROR,
        },
    )
