"""Optional AI features, powered by Claude.

Only used when ANTHROPIC_API_KEY is set on the server *and* the user turned AI on in Settings.
Privacy: we send only what each feature needs - the text you typed/spoke (redacted), a receipt image
you chose to scan, your question, or aggregated monthly totals. Never the full ledger, never account numbers.
"""
import base64
import os
from datetime import date

from pydantic import BaseModel, Field

from .security import redact

MODEL = os.environ.get("ARTHA_MODEL", "claude-opus-5-5")
BETAS = ["server-side-fallback-2026-07-01"]


class AIUnavailable(Exception):
    pass


def available() -> bool:
    return bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"))


def _client():
    if not available():
        raise AIUnavailable("Set ANTHROPIC_API_KEY on the server to use AI features")
    import anthropic
    return anthropic.Anthropic(timeout=60.0)


def _parse(output_format, content, system: str, max_tokens: int = 4000, effort: str = "low"):
    """One structured-output call. Falls back to another model server-side if a request is declined."""
    import anthropic
    client = _client()
    try:
        resp = client.beta.messages.parse(
            model=MODEL,
            max_tokens=max_tokens,
            betas=BETAS,
            fallbacks="default",
            system=system,
            output_config={"effort": effort},
            output_format=output_format,
            messages=[{"role": "user", "content": content}],
        )
    except anthropic.AuthenticationError as e:
        raise AIUnavailable("The server's ANTHROPIC_API_KEY was rejected") from e
    except anthropic.RateLimitError as e:
        raise AIUnavailable("AI is busy right now, try again in a minute") from e
    except anthropic.APIConnectionError as e:
        raise AIUnavailable("Couldn't reach the AI service") from e
    except anthropic.APIStatusError as e:
        raise AIUnavailable(f"AI request failed ({e.status_code})") from e
    if resp.stop_reason == "refusal" or resp.parsed_output is None:
        raise AIUnavailable("AI couldn't process that")
    return resp.parsed_output


# ---------- schemas ----------

class Expense(BaseModel):
    amount: float = Field(description="Amount in rupees, positive")
    merchant: str = Field(description="Who was paid (or who paid, for income). Short proper name, e.g. 'Swiggy'")
    category: str = Field(description="One of the allowed categories")
    kind: str = Field(description="'debit' for money spent, 'credit' for money received")
    date: str = Field(description="ISO date YYYY-MM-DD")
    split_with: list[str] = Field(description="Names of other people sharing this expense; empty if not split")
    split_ways: int = Field(description="Total number of people sharing including the user; 0 if not split")
    confident: bool = Field(description="False if the amount or merchant was a guess")


class ExpenseList(BaseModel):
    expenses: list[Expense]


class Receipt(BaseModel):
    merchant: str
    total: float = Field(description="Final amount paid, including taxes and tip, in rupees")
    date: str = Field(description="ISO date YYYY-MM-DD from the receipt; empty string if not visible")
    category: str = Field(description="One of the allowed categories")
    items: list[str] = Field(description="Up to 8 short line-item descriptions")
    confident: bool = Field(description="False if the total or merchant is hard to read")


class QueryPlan(BaseModel):
    date_from: str = Field(description="ISO date or empty string for no lower bound")
    date_to: str = Field(description="ISO date or empty string for no upper bound")
    categories: list[str] = Field(description="Categories to include; empty for all")
    merchant: str = Field(description="Merchant name fragment to match; empty for any")
    text: str = Field(description="Free-text keywords to match in merchant/note; empty for none")
    kind: str = Field(description="'debit' (spending), 'credit' (money received) or 'any'")
    min_amount: float = Field(description="Minimum amount, 0 for none")
    max_amount: float = Field(description="Maximum amount, 0 for none")
    aggregate: str = Field(description="'sum', 'count', 'list' or 'by_category'")


class Insights(BaseModel):
    headline: str = Field(description="One sentence summary of the month")
    insights: list[str] = Field(description="3-5 specific observations with numbers")
    recommendations: list[str] = Field(description="2-4 concrete, personalised actions to spend less or save more")


# ---------- features ----------

def parse_expenses(text: str, categories: list[str], today: date) -> list[Expense]:
    """Natural-language or voice entry: 'paid 1200 for dinner at Toit yesterday, split with Rahul and Priya'."""
    system = (
        "You turn a person's spoken or typed note about money into structured expenses for an Indian personal "
        f"finance app. Currency is INR. Today is {today.isoformat()} ({today.strftime('%A')}). Resolve relative dates. "
        f"Allowed categories: {', '.join(categories)}. Return one entry per distinct payment; return an empty list "
        "if the note contains no payment."
    )
    return _parse(ExpenseList, redact(text), system).expenses


def scan_receipt(data: bytes, media_type: str, categories: list[str], today: date) -> Receipt:
    b64 = base64.standard_b64encode(data).decode()
    if media_type == "application/pdf":
        block = {"type": "document", "source": {"type": "base64", "media_type": media_type, "data": b64}}
    else:
        block = {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": b64}}
    system = (
        "You read receipts and invoices for an Indian expense tracker. Extract the merchant, the final total paid "
        f"in INR, the date and a category. Today is {today.isoformat()}. Allowed categories: {', '.join(categories)}."
    )
    return _parse(Receipt, [block, {"type": "text", "text": "Extract this receipt."}], system, effort="medium")


def plan_query(question: str, categories: list[str], today: date) -> dict:
    system = (
        "You convert questions about a person's spending into a database filter for an Indian expense tracker. "
        f"Today is {today.isoformat()} ({today.strftime('%A')}); weeks start Monday. "
        f"Categories: {', '.join(categories)}. Use 'sum' for 'how much', 'count' for 'how many', 'by_category' for "
        "breakdowns, and 'list' for 'show/find'. Map loose words to categories (e.g. 'eating out' -> Food & Dining)."
    )
    p = _parse(QueryPlan, question, system)
    return {
        "date_from": p.date_from or None, "date_to": p.date_to or None,
        "categories": [c for c in p.categories if c in categories], "merchant": p.merchant or None,
        "text": p.text or None, "kind": p.kind if p.kind in ("debit", "credit") else None,
        "min_amount": p.min_amount or None, "max_amount": p.max_amount or None,
        "aggregate": p.aggregate if p.aggregate in ("sum", "count", "list", "by_category") else "list",
    }


def monthly_insights(stats: dict) -> Insights:
    """Write insights from aggregated numbers only (category totals, top merchants, budget status)."""
    system = (
        "You are a friendly, practical personal finance coach for someone in India. You get aggregated spending "
        "numbers for one month (INR) plus the previous month, their daily budget status, subscriptions and suggested "
        "budgets. Be specific, use the numbers, use ₹ and Indian digit grouping, keep each point to one sentence, "
        "and never invent data that is not provided."
    )
    import json
    return _parse(Insights, json.dumps(stats, default=str), system, max_tokens=8000, effort="medium")
