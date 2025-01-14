# Artha.AI

## Overview
Artha.AI is a fintech platform designed to empower users with low-risk investment preferences by providing personalized mutual fund recommendations. Built with cutting-edge AI technologies, it analyzes users' financial behavior, bank balances, and transaction histories to offer tailored investment solutions, ensuring financial growth with minimal risk.

---

## Features
- **Personalized Recommendations**: Suggests mutual funds based on user profiles and financial patterns.
- **AI-Powered Insights**: Utilizes AI to analyze transaction history and spending behavior.
- **Low-Risk Focus**: Ideal for users seeking secure investment options outside the stock market.
- **Seamless Integration**: Built with a scalable and secure architecture using Flask and GROQ.

---

## Tech Stack
- **Backend**: Flask
- **AI Frameworks**: GROQ for high-performance computations
- **Database**: SQLite/PostgreSQL (configurable)
- **Frontend**: React (optional for full-stack integration)
- **APIs**: Mutual fund APIs for fetching and analyzing investment options

---

## Installation
1. **Clone the repository**:
   ```bash
   git clone https://github.com/username/artha-ai.git
   cd artha-ai
   ```
2. **Set up a virtual environment**:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   ```
3. **Install dependencies**:
   ```bash
   pip install -r requirements.txt
   ```
4. **Configure environment variables**:
   - Create a `.env` file in the root directory.
   - Add the following variables:
     ```env
     GROQ_API_KEY=<your_groq_api_key>
     DATABASE_URL=<your_database_url>
     ```
5. **Run the application**:
   ```bash
   flask run
   ```
