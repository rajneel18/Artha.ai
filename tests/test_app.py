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
    monkeypatch.setenv("ARTHA_TOKEN", "s3cret")
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    from app import security
    security._failures.clear()
    from app.main import app
    with TestClient(app, headers={"X-Token": "s3cret"}) as c:
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
    assert client.post("/api/sms", json={"message": sms}).json()["added"] == 1
    assert client.post("/api/sms", json={"message": sms}).json()["duplicates"] == 1
    assert client.post("/api/sms", content="x", headers={"X-Token": ""}).status_code == 401
    assert client.post("/api/sms?token=s3cret", content="Rs 50 paid to chai point UPI",
                       headers={"X-Token": ""}).status_code == 200
    # main API never accepts the token in the URL
    assert client.get("/api/summary?token=s3cret", headers={"X-Token": ""}).status_code == 401


def test_csv_import(client):
    csv_data = "Date,Narration,Withdrawal Amt,Deposit Amt\n10/11/2026,UPI-BLINKIT-123,320.00,\n11/11/2026,SALARY,,50000\n"
    r = client.post("/api/import/csv", files={"file": ("s.csv", csv_data, "text/csv")}).json()
    assert r["added"] == 2


def test_pwa_routes(client):
    assert client.get("/share?text=hi").status_code == 200
    m = client.get("/manifest.webmanifest")
    assert m.status_code == 200 and m.json()["share_target"]["action"] == "/share"
    assert "javascript" in client.get("/sw.js").headers["content-type"]
    assert client.get("/icons/icon-192.png").status_code == 200
    assert client.get("/icons/..%2Fmain.py").status_code == 404


def _ids(client, text):
    return [t["id"] for t in client.post("/api/ingest", json={"text": text}).json()["added"]]


def test_non_transactions_and_redaction(client):
    r = client.post("/api/ingest", json={"text": "Your OTP is 482913 for txn of Rs 2000\n\n"
                                                  "Rs 999 will be debited on 20-11-26 for your SIP\n\n"
                                                  "Rs 300 debited from A/c 123456789012 to VPA chai@ybl. Avl Bal Rs 45,000"}).json()
    assert r["ignored"] == 2 and len(r["added"]) == 1
    note = r["added"][0]["note"]
    assert "123456789012" not in note and "45,000" not in note and "9012" in note


def test_clarification_only_when_needed(client):
    known, unknown = _ids(client, "250 swiggy\n700 zxqv traders")
    review = client.get("/api/review").json()
    assert [t["id"] for t in review] == [unknown] and review[0]["question"]
    client.post(f"/api/review/{unknown}", json={"action": "confirm", "category": "Shopping"})
    assert client.get("/api/review").json() == []
    # learned: next payment to the same merchant needs no question
    _ids(client, "300 zxqv traders")
    assert client.get("/api/review").json() == []


def test_duplicate_payment_detection(client):
    client.post("/api/sms", json={"message": "Rs.649 debited from A/c XX1234 on 13-11-26 to VPA blinkit@hdfc UPI Ref 1"})
    client.post("/api/sms", json={"message": "Sent Rs.649.00 From HDFC Bank A/C *1234 To BLINKIT On 13/11/26 Ref 1"})
    review = client.get("/api/review").json()
    assert len(review) == 1 and review[0]["review_reason"] == "duplicate"
    client.post(f"/api/review/{review[0]['id']}", json={"action": "delete"})
    assert len(client.get("/api/transactions").json()) == 1


def test_allowance_and_savings_pool(client):
    client.put("/api/settings", json={"daily_allowance": 1000, "budget_start": "2026-11-10"})
    client.post("/api/transactions", json={"amount": 400, "merchant": "Swiggy", "date": "2026-11-10"})
    client.post("/api/transactions", json={"amount": 1500, "merchant": "Zara", "date": "2026-11-11"})
    client.post("/api/transactions", json={"amount": 20000, "merchant": "Landlord", "category": "Rent", "date": "2026-11-11"})
    from app import main
    b = client.get("/api/budget").json()
    days = (main.today() - date(2026, 11, 10)).days
    # rent is excluded; pool = days*1000 - 400 - 1500
    assert b["savings_pool"] == days * 1000 - 1900
    assert b["daily_allowance"] == 1000


def test_split_and_auto_settle(client):
    tx = client.post("/api/ingest", json={"text": "900 dinner at toit split with rahul and priya"}).json()["added"][0]
    assert tx["my_share"] == 300 and {s["person"] for s in tx["splits"]} == {"Rahul", "Priya"}
    assert {b["person"]: b["owed"] for b in client.get("/api/splits").json()} == {"Rahul": 300, "Priya": 300}
    client.post("/api/ingest", json={"text": "Rs 300 credited to your A/c XX1234 from Rahul Sharma via UPI"})
    assert [b["person"] for b in client.get("/api/splits").json()] == ["Priya"]
    assert client.get("/api/summary").json()["today"] == 300  # only my share counts


def test_recurring_and_subscriptions(client):
    for d in ("2026-08-05", "2026-09-05", "2026-10-05"):
        client.post("/api/transactions", json={"amount": 649, "merchant": "Spotify", "date": d, "note": "UPI"})
    client.post("/api/sms", json={"message": "Rs.199 debited from A/c XX1 to VPA netflix@icici UPI Ref 9"})
    subs = client.get("/api/subscriptions").json()
    names = {s["name"]: s for s in subs["subscriptions"]}
    assert names["Spotify"]["status"] == "active" and names["Spotify"]["next_date"] == "2026-11-05"
    assert names["Netflix"]["status"] == "suggested"
    sid = names["Spotify"]["id"]
    client.patch(f"/api/subscriptions/{sid}", json={"status": "cancelled"})
    client.post("/api/transactions", json={"amount": 649, "merchant": "Spotify", "date": "2026-11-05"})
    assert any(t["review_reason"] == "cancelled_subscription" for t in client.get("/api/review").json())


def test_custom_rules_and_categories(client):
    client.put("/api/settings", json={"custom_categories": ["Pets"]})
    [tid] = _ids(client, "1200 heads up for tails")
    r = client.post("/api/rules", json={"pattern": "tails", "category": "Pets"}).json()
    assert r["updated_transactions"] == 1
    assert client.get(f"/api/transactions/{tid}").json()["category"] == "Pets"
    client.post("/api/rules", json={"pattern": r"^uber", "match_type": "regex", "category": "Travel", "min_amount": 1000})
    a, b = _ids(client, "uber 1500\nuber 200")
    assert client.get(f"/api/transactions/{a}").json()["category"] == "Travel"
    assert client.get(f"/api/transactions/{b}").json()["category"] == "Transport"


def test_ask_search_merchant_history_insights(client):
    _ids(client, "250 swiggy\n400 zomato\nuber 180")
    r = client.post("/api/ask", json={"question": "how much did I spend on food this month?"}).json()
    assert r["total"] == 650 and "650" in r["answer"] and not r["used_ai"]
    r = client.post("/api/ask", json={"question": "show uber"}).json()
    assert r["count"] == 1
    h = client.get("/api/merchants/history", params={"name": "Swiggy"}).json()
    assert h["total"] == 250 and len(h["monthly"]) == 12
    ins = client.get("/api/insights").json()
    assert ins["insights"] and ins["recommendations"]["suggested_daily_allowance"] > 0
    assert client.post("/api/insights/ai").status_code == 400  # AI off by default


def test_user_controlled_deletion(client):
    _ids(client, "250 swiggy\n100 chai")
    assert client.post("/api/data/delete", json={"scope": "everything", "confirm": "nope"}).status_code == 400
    r = client.post("/api/data/delete", json={"scope": "everything", "confirm": "DELETE"}).json()
    assert r["remaining_transactions"] == 0
    assert client.get("/api/transactions").json() == []


def test_security_headers_and_throttle(client):
    h = client.get("/").headers
    assert "frame-ancestors 'none'" in h["content-security-policy"] and h["x-content-type-options"] == "nosniff"
    assert client.get("/api/summary").headers["cache-control"] == "no-store"
    for _ in range(10):
        client.get("/api/summary", headers={"X-Token": "wrong"})
    assert client.get("/api/summary", headers={"X-Token": "wrong"}).status_code == 429


def test_ai_features_with_mocked_claude(client, monkeypatch):
    from app import ai
    monkeypatch.setattr(ai, "available", lambda: True)
    client.put("/api/settings", json={"ai_enabled": True})
    monkeypatch.setattr(ai, "parse_expenses", lambda text, cats, today: [ai.Expense(
        amount=450, merchant="Third Wave Coffee", category="Food & Dining", kind="debit", date=str(today),
        split_with=["Asha"], split_ways=2, confident=True)])
    r = client.post("/api/ingest", json={"text": "coffee with asha four fifty we split it", "smart": True}).json()
    assert r["added"][0]["merchant"] == "Third Wave Coffee" and r["added"][0]["my_share"] == 225
    monkeypatch.setattr(ai, "scan_receipt", lambda data, media, cats, today: ai.Receipt(
        merchant="DMart", total=1834.5, date="2026-11-12", category="Groceries", items=["Rice", "Dal"], confident=True))
    r = client.post("/api/receipt", files={"file": ("r.jpg", b"\xff\xd8fake", "image/jpeg")}).json()
    assert r["transaction"]["amount"] == 1834.5 and r["transaction"]["source"] == "receipt"
