FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY requirements.txt ./requirements.txt
RUN python -m pip install --no-cache-dir --require-hashes -r requirements.txt \
    && python -c "import jionlp" \
    && groupadd --system parser \
    && useradd --system --gid parser --home-dir /app --no-create-home parser

COPY app.py ./app.py

USER parser

EXPOSE 8081

ENTRYPOINT ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8081"]
