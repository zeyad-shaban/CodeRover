from pydantic import BaseModel
import uvicorn
from fastapi.staticfiles import StaticFiles
from datetime import datetime
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import create_engine, Column, Integer, Float, DateTime
from sqlalchemy.orm import declarative_base, sessionmaker
import os

# Database setup
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATABASE_URL = f"sqlite:///{os.path.join(BASE_DIR, 'database.db')}"

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(bind=engine)
Base = declarative_base()


class Control(Base):
    __tablename__ = "controls"
    id = Column(Integer, primary_key=True)
    x = Column(Float)
    y = Column(Float)
    timestamp = Column(DateTime, default=datetime.utcnow)


# This will NOT overwrite your file; it only adds tables if they are missing
Base.metadata.create_all(bind=engine)

app = FastAPI()
templates = Jinja2Templates(directory="./static")


class ControlData(BaseModel):
    x: float
    y: float


@app.get("/", response_class=HTMLResponse)
def read_root(request: Request):
    # Changed from gui.html to index.html to match your provided file name
    return templates.TemplateResponse("index.html", {"request": request})


@app.post("/control")
def receive_control(data: ControlData):
    db = SessionLocal()
    entry = Control(x=data.x, y=data.y)
    db.add(entry)
    db.commit()
    db.close()
    return {"status": "ok"}


# NEW ROUTE: Fetch logs for the popup
@app.get("/logs")
def get_logs():
    db = SessionLocal()
    # Get last 50 entries
    logs = db.query(Control).order_by(Control.timestamp.desc()).limit(50).all()
    db.close()
    return [{"x": l.x, "y": l.y, "time": l.timestamp.strftime("%H:%M:%S")} for l in logs]


app.mount("/static", StaticFiles(directory="./static"), name="static")

if __name__ == "__main__":
    uvicorn.run(app="server:app", port=7002, reload=True)
