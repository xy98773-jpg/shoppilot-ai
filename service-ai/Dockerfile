# ShopPilot AI service image
FROM python:3.13-slim

WORKDIR /app

# Install Python dependencies first for better layer caching.
COPY requirements.txt pyproject.toml ./
RUN pip install --no-cache-dir -r requirements.txt

# Install the service package in editable mode and copy sources.
COPY . .
RUN pip install --no-cache-dir -e .

EXPOSE 8000

CMD ["uvicorn", "shoppilot_ai.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
