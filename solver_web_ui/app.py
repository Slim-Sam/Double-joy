"""Web preview server for the Двойная радость solver UI.

Serves the HTML interface (base_ui_source.html) on port 8000.
"""
import os
from pathlib import Path

from fastapi import FastAPI, Response
from fastapi.responses import HTMLResponse

app = FastAPI(title="Двойная радость — Решалка")

HTML_PATH = Path(__file__).parent.parent / "double_joy_android_apk" / "base_ui_source.html"

@app.get("/")
async def index():
    if not HTML_PATH.exists():
        return HTMLResponse("<h1>HTML not found</h1>", status_code=500)
    html = HTML_PATH.read_text(encoding="utf-8")
    return HTMLResponse(html)

@app.get("/health")
async def health():
    return {"status": "ok", "html_exists": HTML_PATH.exists()}
