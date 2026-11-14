from datetime import date

import pytest
from fastapi.testclient import TestClient

from app import db
from app.categorizer import categorize
from app.parser import parse_message

TODAY = date(2026, 11, 14)


@pytest.mark.parametrize("text,amount,merchant,cat", [
    ("250 swiggy dinner", 250, "Swiggy Dinner", "Food & Dining"),
    ("petrol 1,500", 1500, "Petrol", "Fuel"),
    ("spent 120 on uber", 120, "Uber", "Transport"),
    ("Rs.499.00 debited from A/c XX1234 on 12-11-26 to VPA netflix@icici UPI Ref 123456",
     499, "Netflix", "Entertainment"),
    ("INR 1,234.50 spent on HDFC Bank Card XX9876 at AMAZON on 2026-11-10. Avl Lmt: INR 50,000",
     1234.5, "Amazon", "Shopping"),
    ("Sent Rs.85.00 From HDFC Bank A/C *1234 To ZEPTO On 13/11/26 Ref 999", 85, "Zepto", "Groceries"),
])
def test_parse_and_categorize(text, amount, merchant, cat):
    p = parse_message(text, today=TODAY)
    assert p is not None
    assert p.amount == amount
    assert p.merchant == merchant
    assert categorize(p.note, p.merchant) == cat


def test_credit_and_dates():
    p = parse_message("Rs 5000 credited to your A/c XX1234 on 01-11-26 by NEFT", today=TODAY)
    assert p.kind == "credit" and p.date == date(2026, 11, 1)
    assert parse_message("coffee 90 yesterday", today=TODAY).date == date(2026, 11, 13)
    assert parse_message("hello there", today=TODAY) is None


def test_learned_rule_wins():
    assert categorize("400 ramesh", "Ramesh", {"ramesh": "Groceries"}) == "Groceries"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "t.db"))
    monkeypatch.delenv("ARTHA_TOKEN", raising=False)
    from app.main import app
    with TestClient(app) as c:
        yield c


def test_api_flow(client):
    r = client.post("/api/ingest", json={"text": "250 swiggy\n400 ramesh kirana store"}).json()
    assert len(r["added"]) == 2
    tx = {t["merchant"]: t for t in client.get("/api/transactions").json()}
    assert tx["Swiggy"]["category"] == "Food & Dining"

    # Correcting a category teaches the app.
    rid = tx["Ramesh Kirana Store"]["id"]
    client.patch(f"/api/transactions/{rid}", json={"category": "Groceries"})
    client.post("/api/ingest", json={"text": "100 ramesh kirana store"})
    cats = [t["category"] for t in client.get("/api/transactions").json() if t["merchant"] == "Ramesh Kirana Store"]
    assert cats == ["Groceries", "Groceries"]

    s = client.get("/api/summary").json()
    assert s["spent"] == 750


def test_sms_webhook_dedup_and_token(client, monkeypatch):
    sms = "Rs.499.00 debited from A/c XX1234 on 12-11-26 to VPA netflix@icici UPI Ref 123456"
    assert len(client.post("/api/sms", json={"message": sms}).json()["added"]) == 1
    assert client.post("/api/sms", json={"message": sms}).json()["duplicates"] == 1
    monkeypatch.setenv("ARTHA_TOKEN", "s3cret")
    assert client.post("/api/sms", content="x").status_code == 401
    assert client.post("/api/sms?token=s3cret", content="Rs 50 paid to chai point UPI").status_code == 200


def test_csv_import(client):
    csv_data = "Date,Narration,Withdrawal Amt,Deposit Amt\n10/11/2026,UPI-BLINKIT-123,320.00,\n11/11/2026,SALARY,,50000\n"
    r = client.post("/api/import/csv", files={"file": ("s.csv", csv_data, "text/csv")}).json()
    assert r["added"] == 2
