"""Turn free text or bank / UPI SMS messages into structured transactions."""
import re
from dataclasses import dataclass, field
from datetime import date, datetime

AMOUNT_RE = re.compile(
    r"(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.\d{1,2})?)|([0-9][0-9,]*(?:\.\d{1,2})?)\s*(?:rs\.?|inr|₹|rupees|/-)",
    re.I,
)
BARE_AMOUNT_RE = re.compile(r"(?<![\w.])([0-9][0-9,]*(?:\.\d{1,2})?)(?![\w.])")

DEBIT_WORDS = re.compile(r"\b(debited|spent|paid|sent|withdrawn|purchase|dr\b|debit)", re.I)
CREDIT_WORDS = re.compile(r"\b(credited|received|refund|cashback|deposited|cr\b)", re.I)
SMS_HINT = re.compile(r"\b(a/c|acct|account|upi|vpa|ref|avl bal|card|xx\d+|\*\d{3,})", re.I)

MERCHANT_PATTERNS = [
    re.compile(r"\b(?:to|towards)\s+VPA\s+([\w.\-]+@[\w.\-]+)", re.I),
    re.compile(r"\binfo:?\s*(?:upi/)?(?:[\w]+/)?([A-Za-z][\w .&'-]{2,40})", re.I),
    re.compile(r"\bat\s+([A-Za-z][\w .&'@-]{1,40}?)(?=\s+(?:on|via|ref|upi|using|from|for|txn|dated|avl|\d{1,2}[-/])\b|[.,;]|$)", re.I),
    re.compile(r"\bto\s+([A-Za-z][\w .&'@-]{1,40}?)(?=\s+(?:on|via|ref|upi|using|from|for|txn|dated|avl|\d{1,2}[-/])\b|[.,;]|$)", re.I),
    re.compile(r"\bon\s+([A-Za-z][\w .&'@-]{1,40}?)(?=\s+(?:on|via|ref|upi|using|from|for|txn|dated|avl|\d{1,2}[-/])\b|[.,;]|$)", re.I),
    re.compile(r";\s*([A-Za-z][\w .&'-]{2,40}?)\s+credited", re.I),
    re.compile(r"\b(?:from|by)\s+([A-Za-z][\w .&'@-]{1,40}?)(?=\s+(?:on|via|ref|upi|using|for|txn|dated|avl|\d{1,2}[-/])\b|[.,;]|$)", re.I),
]
DATE_PATTERNS = [
    ("%d-%m-%y", re.compile(r"\b(\d{1,2}-\d{1,2}-\d{2})\b")),
    ("%d-%m-%Y", re.compile(r"\b(\d{1,2}-\d{1,2}-\d{4})\b")),
    ("%d/%m/%y", re.compile(r"\b(\d{1,2}/\d{1,2}/\d{2})\b")),
    ("%d/%m/%Y", re.compile(r"\b(\d{1,2}/\d{1,2}/\d{4})\b")),
    ("%d-%b-%y", re.compile(r"\b(\d{1,2}-[A-Za-z]{3}-\d{2})\b")),
    ("%d-%b-%Y", re.compile(r"\b(\d{1,2}-[A-Za-z]{3}-\d{4})\b")),
    ("%d%b%y", re.compile(r"\b(\d{1,2}[A-Za-z]{3}\d{2})\b")),
    ("%Y-%m-%d", re.compile(r"\b(\d{4}-\d{2}-\d{2})\b")),
]
# Messages that mention money but are not a completed payment: OTPs, failures, reminders, requests.
NON_TXN = re.compile(
    r"(?i)\b(otp|one[- ]time password|verification code|failed|declined|unsuccessful|could not be processed|"
    r"will be debited|to be debited|is due|due on|due date|payment due|min(?:imum)? amt|minimum amount due|"
    r"collect request|has requested|requested (?:rs|inr|money)|statement (?:for|generated)|pre-?approved|"
    r"loan of)\b")
SPLIT_RE = re.compile(r"(?i)\b(?:split|shared?|divide[d]?)\b(?:\s+(?:it|this|bill))?"
                      r"(?:\s+(?:with|between|among)\s+(?P<people>[a-z ,&]+?))?(?:\s+(?P<ways>\d+|two|three|four|five|six)\s+ways?)?(?=$|[.;]|\s+(?:on|for|at|today|yesterday)\b)")
WORD_NUM = {"two": 2, "three": 3, "four": 4, "five": 5, "six": 6}
STOPWORDS = {"on", "for", "at", "to", "spent", "paid", "rs", "inr", "rupees", "the", "a", "my"}


@dataclass
class Parsed:
    amount: float
    kind: str  # "debit" or "credit"
    merchant: str
    note: str
    date: date
    source: str  # "sms" or "text"
    confident: bool = True
    split_with: list[str] = field(default_factory=list)
    split_ways: int | None = None


def _to_float(s: str) -> float:
    return float(s.replace(",", ""))


def _find_amount(text: str) -> float | None:
    m = AMOUNT_RE.search(text)
    if m:
        return _to_float(m.group(1) or m.group(2))
    # Free text like "250 swiggy": take the first bare number that isn't a date/account fragment.
    for m in BARE_AMOUNT_RE.finditer(text):
        val = m.group(1)
        if len(val.replace(",", "").split(".")[0]) <= 7:
            return _to_float(val)
    return None


def _find_date(text: str, today: date) -> date:
    for fmt, rx in DATE_PATTERNS:
        m = rx.search(text)
        if m:
            try:
                d = datetime.strptime(m.group(1), fmt).date()
                if d <= today:
                    return d
            except ValueError:
                continue
    low = text.lower()
    if "yesterday" in low:
        return date.fromordinal(today.toordinal() - 1)
    return today


def _clean_merchant(raw: str) -> str:
    raw = raw.strip(" .,-;:")
    if "@" in raw:  # UPI VPA like swiggy.instamart@icici -> "swiggy instamart"
        raw = raw.split("@")[0].replace(".", " ").replace("_", " ")
    raw = re.sub(r"\s+", " ", raw)
    return raw.title()[:40]


def _find_merchant(text: str, is_sms: bool) -> str:
    if is_sms:
        for rx in MERCHANT_PATTERNS:
            m = rx.search(text)
            if m:
                cand = _clean_merchant(m.group(1))
                if cand and not re.fullmatch(r"(?i)(your|a/c|ac|acct|account|card).*", cand) \
                        and not re.search(r"(?i)\b(bank|card|a/c|xx\d+)\b", cand):
                    return cand
        return "Unknown"
    # free text: everything that isn't the amount or a filler word
    words = [w for w in re.split(r"\s+", AMOUNT_RE.sub(" ", text)) if w]
    words = [w for w in words if not BARE_AMOUNT_RE.fullmatch(w) and w.lower() not in STOPWORDS
             and w.lower() not in {"today", "yesterday"}]
    return _clean_merchant(" ".join(words[:3])) if words else "Unknown"


def is_non_transaction(text: str) -> bool:
    return bool(NON_TXN.search(text or ""))


def _find_split(text: str) -> tuple[str, list[str], int | None]:
    m = SPLIT_RE.search(text)
    if not m:
        return text, [], None
    people = []
    if m.group("people"):
        people = [p.strip().title() for p in re.split(r",|&|\band\b", m.group("people")) if p.strip()]
        people = [p for p in people if p.lower() not in {"me", "i", "myself"}]
    ways = m.group("ways")
    ways = int(ways) if ways and ways.isdigit() else WORD_NUM.get((ways or "").lower())
    return (text[:m.start()] + " " + text[m.end():]).strip(), people, ways


def parse_message(text: str, today: date | None = None) -> Parsed | None:
    """Parse one line / one SMS. Returns None if no amount can be found or it isn't a real payment."""
    today = today or date.today()
    text = (text or "").strip()
    if not text or is_non_transaction(text):
        return None
    amount = _find_amount(text)
    if amount is None or amount <= 0:
        return None
    is_sms = bool(SMS_HINT.search(text)) or bool(DEBIT_WORDS.search(text) and len(text) > 60)
    kind = "credit" if CREDIT_WORDS.search(text) and not DEBIT_WORDS.search(text) else "debit"
    # SMS like "A/c debited ... ; X credited" mentions both - debit wins, which we already do.
    body, split_with, split_ways = (text, [], None) if is_sms else _find_split(text)
    merchant = _find_merchant(body, is_sms)
    explicit_amount = bool(AMOUNT_RE.search(text))
    return Parsed(
        amount=amount,
        kind=kind,
        merchant=merchant,
        note=text,
        date=_find_date(text, today),
        source="sms" if is_sms else "text",
        confident=merchant != "Unknown" and (explicit_amount or not is_sms),
        split_with=split_with,
        split_ways=split_ways,
    )
