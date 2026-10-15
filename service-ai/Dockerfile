# ShopPilot AI service image
FROM python:3.13-slim

WORKDIR /app

# Install Python dependencies first for better layer caching.
COPY requirements.txt pyproject.toml ./
# 加固：放大 pip 重试/超时，规避网络抖动（与 frontend-web Dockerfile 同口径）
RUN pip install --no-cache-dir --retries 5 --timeout 120 -r requirements.txt

# Install the service package in editable mode and copy sources.
COPY . .
RUN pip install --no-cache-dir -e .

EXPOSE 8000

CMD ["uvicorn", "shoppilot_ai.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
