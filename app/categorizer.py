"""Rule-based categorizer with learned merchant overrides."""
import re

CATEGORIES = [
    "Food & Dining",
    "Groceries",
    "Transport",
    "Fuel",
    "Shopping",
    "Bills & Utilities",
    "Rent",
    "Entertainment",
    "Health",
    "Education",
    "Travel",
    "Investments",
    "Transfers",
    "Personal Care",
    "Other",
]

# keyword -> category. Matched as whole words / substrings against the lowercased text.
KEYWORDS = {
    "Food & Dining": [
        "swiggy", "zomato", "restaurant", "cafe", "coffee", "starbucks", "dominos", "domino",
        "pizza", "mcdonald", "kfc", "burger", "biryani", "dinner", "lunch", "breakfast",
        "snacks", "tea", "chai", "eatfit", "dunzo food", "barbeque", "hotel food", "canteen",
    ],
    "Groceries": [
        "blinkit", "zepto", "bigbasket", "big basket", "instamart", "dmart", "d-mart",
        "grocery", "groceries", "reliance fresh", "more supermarket", "jiomart", "vegetables",
        "milk", "kirana", "supermarket", "fruits",
    ],
    "Transport": [
        "uber", "ola", "rapido", "metro", "auto", "cab", "taxi", "bus", "irctc", "train",
        "local train", "parking", "toll", "fastag", "namma yatri",
    ],
    "Fuel": ["petrol", "diesel", "fuel", "hpcl", "bpcl", "indian oil", "iocl", "shell", "cng"],
    "Shopping": [
        "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "croma", "decathlon",
        "shopping", "clothes", "shoes", "lenskart", "ikea", "tata cliq",
    ],
    "Bills & Utilities": [
        "electricity", "bescom", "msedcl", "tata power", "adani electricity", "water bill",
        "gas bill", "broadband", "wifi", "airtel", "jio", "vodafone", "vi recharge", "bsnl",
        "recharge", "mobile bill", "postpaid", "dth", "tata play", "act fibernet", "bill",
        "insurance", "lic premium",
    ],
    "Rent": ["rent", "landlord", "nobroker", "maintenance", "society"],
    "Entertainment": [
        "netflix", "prime video", "hotstar", "spotify", "youtube premium", "bookmyshow",
        "pvr", "inox", "movie", "concert", "gaming", "steam", "playstation", "sonyliv", "jiocinema",
    ],
    "Health": [
        "pharmacy", "medical", "medicine", "apollo", "pharmeasy", "1mg", "netmeds", "hospital",
        "clinic", "doctor", "lab test", "practo", "gym", "cult.fit", "cultfit",
    ],
    "Education": ["course", "udemy", "coursera", "books", "tuition", "fees", "college", "school", "exam"],
    "Travel": [
        "makemytrip", "goibibo", "cleartrip", "indigo", "air india", "vistara", "akasa",
        "spicejet", "flight", "hotel", "oyo", "airbnb", "booking.com", "ixigo", "redbus",
    ],
    "Investments": [
        "zerodha", "groww", "upstox", "sip", "mutual fund", "kuvera", "coin", "ppf", "nps",
        "smallcase", "angel one", "fd ", "fixed deposit",
    ],
    "Transfers": ["transfer", "neft", "imps", "sent to", "self transfer", "credit card payment", "cc payment"],
    "Personal Care": ["salon", "haircut", "spa", "urban company", "parlour", "barber"],
}


def _norm(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").lower()).strip()


def categorize(text: str, merchant: str | None = None, learned: dict[str, str] | None = None) -> str:
    """Return the best category for a transaction.

    `learned` maps a normalized merchant name to a category the user picked earlier;
    it always wins over built-in keywords.
    """
    m = _norm(merchant or "")
    if learned and m:
        if m in learned:
            return learned[m]
        for key, cat in learned.items():
            if key and (key in m or m in key):
                return cat

    haystack = f" {_norm(merchant or '')} {_norm(text)} "
    best, best_len = "Other", 0
    for cat, words in KEYWORDS.items():
        for w in words:
            # prefer the longest matching keyword so "reliance fresh" beats "fresh"
            if re.search(rf"(?<![a-z0-9]){re.escape(w)}(?![a-z0-9])", haystack) and len(w) > best_len:
                best, best_len = cat, len(w)
    return best


def merchant_key(merchant: str | None) -> str:
    return _norm(merchant or "")
